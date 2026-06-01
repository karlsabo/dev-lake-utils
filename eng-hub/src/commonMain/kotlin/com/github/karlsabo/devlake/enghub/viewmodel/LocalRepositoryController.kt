package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalRepositoryUiStates
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class LocalRepositoryController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val errorReporter: ActionErrorReporter,
) {
    private val gitWorktreeApi: GitWorktreeApi = worktreeServices.gitWorktreeApi
    private val expansionTracker = LocalRepositoryExpansionTracker(state)

    fun pickAndAddLocalRepository() {
        viewModel.viewModelScope.launch {
            val selectedPath = worktreeServices.directoryPicker.pickDirectory("Add Local Repository")
            if (selectedPath != null) {
                addLocalRepository(selectedPath)
            }
        }
    }

    fun addLocalRepository(selectedPath: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { addLocalRepositoryBlocking(selectedPath) }
                .rethrowCancellation()
                .onFailure { failure ->
                    logger.error(failure) { "Failed to add local repository from $selectedPath" }
                    errorReporter.enqueueActionError(failure.message ?: "Failed to add local repository")
                }
        }
    }

    fun toggleLocalRepositoryExpansion(repoRootPath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val repository = state.localRepositories.value.firstOrNull {
            it.path.normalizedRepoPath() == normalizedRepoRootPath
        }
        when {
            repository == null -> Unit

            repository.isExpanded -> collapseLocalRepository(normalizedRepoRootPath)

            expansionTracker.markInFlight(normalizedRepoRootPath) -> expandLocalRepository(
                repoRootPath,
                normalizedRepoRootPath,
            )
        }
    }

    suspend fun pollConfiguredLocalRepositoryWorktrees() {
        while (true) {
            delay(state.currentConfig.worktreePollIntervalMs.coerceAtLeast(1).milliseconds)
            refreshConfiguredLocalRepositoryWorktrees()
        }
    }

    fun updateLocalRepositoryWorktrees(
        repoRootPath: String,
        worktrees: List<LocalWorktreeUiState>,
        isExpanded: Boolean? = null,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        state.localRepositories.update { repositories ->
            repositories.map { currentRepository ->
                if (currentRepository.path.normalizedRepoPath() == normalizedRepoRootPath) {
                    currentRepository.copy(
                        isExpanded = isExpanded ?: currentRepository.isExpanded,
                        worktrees = worktrees,
                    )
                } else {
                    currentRepository
                }
            }
        }
    }

    fun refreshLocalRepositoryWorktreesBestEffort(repoRootPath: String, logContext: String) {
        runCatching {
            updateLocalRepositoryWorktrees(
                repoRootPath,
                gitWorktreeApi.listLocalWorktreeUiStates(repoRootPath),
            )
        }
            .rethrowCancellation()
            .onFailure { failure ->
                logger.error(failure) { "Failed to refresh worktrees $logContext for $repoRootPath" }
            }
    }

    private fun addLocalRepositoryBlocking(selectedPath: String) {
        val repositoryWorktrees = gitWorktreeApi.resolveRepositoryRoot(selectedPath)
        val rootPath = repositoryWorktrees.rootPath
        val alreadyConfigured = state.currentConfig.localRepositories.any {
            it.path.normalizedRepoPath() == rootPath.normalizedRepoPath()
        }
        if (alreadyConfigured) {
            errorReporter.enqueueActionError("Repository already configured: $rootPath")
            return
        }

        val newConfig = state.currentConfig.copy(
            localRepositories = state.currentConfig.localRepositories + LocalRepositoryConfig(path = rootPath),
        )
        worktreeServices.configWriter.save(newConfig)
        state.currentConfig = newConfig
        state.localRepositories.update { repositories ->
            newConfig.localRepositories
                .toLocalRepositoryUiStates()
                .withPreservedWorktrees(
                    previousRepositories = repositories,
                    updatedRootPath = rootPath,
                    updatedWorktrees = repositoryWorktrees.worktrees.toLocalWorktreeUiStates(rootPath),
                    expandUpdatedRepository = true,
                )
        }
    }

    private fun collapseLocalRepository(normalizedRepoRootPath: String) {
        expansionTracker.clear(normalizedRepoRootPath)
        state.localRepositories.update { repositories ->
            repositories.map { currentRepository ->
                if (currentRepository.path.normalizedRepoPath() == normalizedRepoRootPath) {
                    currentRepository.copy(isExpanded = false)
                } else {
                    currentRepository
                }
            }
        }
    }

    private fun expandLocalRepository(repoRootPath: String, normalizedRepoRootPath: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val worktrees = gitWorktreeApi.listLocalWorktreeUiStates(repoRootPath)
                if (expansionTracker.isInFlight(normalizedRepoRootPath)) {
                    updateLocalRepositoryWorktrees(repoRootPath, worktrees, isExpanded = true)
                }
            }
                .rethrowCancellation()
                .onFailure { failure ->
                    logger.error(failure) { "Failed to list worktrees for $repoRootPath" }
                    errorReporter.enqueueActionError(failure.message ?: "Failed to list worktrees")
                }
            expansionTracker.clear(normalizedRepoRootPath)
        }
    }

    private fun refreshConfiguredLocalRepositoryWorktrees() {
        state.currentConfig.localRepositories
            .asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.normalizedRepoPath() }
            .forEach { repoRootPath ->
                runCatching {
                    updateLocalRepositoryWorktrees(
                        repoRootPath,
                        gitWorktreeApi.listLocalWorktreeUiStates(repoRootPath),
                    )
                }
                    .rethrowCancellation()
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to poll worktrees for $repoRootPath" }
                    }
            }
    }
}
