package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
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
    private val refreshTracker = LocalRepositoryRefreshTracker(state)

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

            repository.isExpanded -> expansionTracker.collapse(normalizedRepoRootPath)

            else -> expansionTracker.start(normalizedRepoRootPath)?.let { request ->
                expandLocalRepository(repoRootPath, normalizedRepoRootPath, request)
            }
        }
    }

    suspend fun pollConfiguredLocalRepositoryWorktrees() {
        while (true) {
            delay(state.currentConfig.worktreePollIntervalMs.coerceAtLeast(1).milliseconds)
            refreshConfiguredLocalRepositoryWorktrees()
        }
    }

    fun refreshLocalRepositoryWorktreesBestEffort(repoRootPath: String, logContext: String) {
        runCatching { refreshLocalRepositoryWorktrees(repoRootPath) }
            .rethrowCancellation()
            .onFailure { failure ->
                logger.error(failure) { "Failed to refresh worktrees $logContext for $repoRootPath" }
            }
    }

    private suspend fun addLocalRepositoryBlocking(selectedPath: String) {
        val repositoryWorktrees = gitWorktreeApi.resolveRepositoryRoot(selectedPath)
        val rootPath = repositoryWorktrees.rootPath
        var repositoryAdded = false
        state.updateConfig { currentConfig ->
            val alreadyConfigured = currentConfig.localRepositories.any {
                it.path.normalizedRepoPath() == rootPath.normalizedRepoPath()
            }
            if (alreadyConfigured) {
                currentConfig
            } else {
                repositoryAdded = true
                currentConfig.copy(
                    localRepositories = currentConfig.localRepositories + LocalRepositoryConfig(path = rootPath),
                )
            }
        }
        if (!repositoryAdded) {
            errorReporter.enqueueActionError("Repository already configured: $rootPath")
            return
        }

        val normalizedRootPath = rootPath.normalizedRepoPath()
        val enrichmentRequest = LocalRepositoryWorktreeRequest()
        val basicWorktrees = repositoryWorktrees.worktrees.toLocalWorktreeUiStates(rootPath)
        state.localRepositories.update { repositories ->
            state.currentConfig.localRepositories
                .toLocalRepositoryUiStates()
                .withPreservedWorktrees(
                    previousRepositories = repositories,
                    updatedRootPath = rootPath,
                    updatedWorktrees = basicWorktrees,
                    expandUpdatedRepository = true,
                ).map { repository ->
                    if (repository.path.normalizedRepoPath() == normalizedRootPath) {
                        repository.copy(refreshRequest = enrichmentRequest)
                    } else {
                        repository
                    }
                }
        }

        runCatching { gitWorktreeApi.enrichLocalWorktreeUiStates(rootPath, basicWorktrees) }
            .rethrowCancellation()
            .onSuccess { enrichedWorktrees ->
                refreshTracker.complete(normalizedRootPath, enrichmentRequest, enrichedWorktrees)
            }
            .onFailure { failure ->
                logger.error(failure) { "Failed to enrich worktrees for newly added repository $rootPath" }
                refreshTracker.complete(normalizedRootPath, enrichmentRequest, basicWorktrees)
            }
    }

    private fun expandLocalRepository(
        repoRootPath: String,
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
    ) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val basicWorktrees = runCatching {
                gitWorktreeApi.listWorktrees(repoRootPath).toLocalWorktreeUiStates(repoRootPath)
            }.rethrowCancellation().getOrElse { failure ->
                logger.error(failure) { "Failed to list worktrees for $repoRootPath" }
                if (expansionTracker.complete(normalizedRepoRootPath, request)) {
                    errorReporter.enqueueActionError(failure.message ?: "Failed to list worktrees")
                }
                return@launch
            }
            if (!expansionTracker.publishDiscovered(normalizedRepoRootPath, request, basicWorktrees)) return@launch

            runCatching { gitWorktreeApi.enrichLocalWorktreeUiStates(repoRootPath, basicWorktrees) }
                .rethrowCancellation()
                .onSuccess { worktrees ->
                    expansionTracker.complete(normalizedRepoRootPath, request, worktrees)
                }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to enrich worktrees for $repoRootPath" }
                    expansionTracker.complete(normalizedRepoRootPath, request)
                }
        }
    }

    private fun refreshConfiguredLocalRepositoryWorktrees() {
        state.currentConfig.localRepositories
            .asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.normalizedRepoPath() }
            .forEach { repoRootPath ->
                runCatching { refreshLocalRepositoryWorktrees(repoRootPath) }
                    .rethrowCancellation()
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to poll worktrees for $repoRootPath" }
                    }
            }
    }

    private fun refreshLocalRepositoryWorktrees(repoRootPath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val request = refreshTracker.start(normalizedRepoRootPath) ?: return
        val basicWorktrees = runCatching {
            gitWorktreeApi.listWorktrees(repoRootPath).toLocalWorktreeUiStates(repoRootPath)
        }.onFailure {
            refreshTracker.fail(normalizedRepoRootPath, request)
        }.getOrThrow()
        if (!refreshTracker.publishDiscovered(normalizedRepoRootPath, request, basicWorktrees)) return

        runCatching { gitWorktreeApi.enrichLocalWorktreeUiStates(repoRootPath, basicWorktrees) }
            .rethrowCancellation()
            .onSuccess { enrichedWorktrees ->
                refreshTracker.complete(normalizedRepoRootPath, request, enrichedWorktrees)
            }
            .onFailure { failure ->
                logger.error(failure) { "Failed to enrich worktrees for $repoRootPath" }
                refreshTracker.complete(normalizedRepoRootPath, request, basicWorktrees)
            }
    }
}

internal class LocalRepositoryRefreshTracker(
    private val state: EngHubViewModelState,
) {
    fun start(normalizedRepoRootPath: String): LocalRepositoryWorktreeRequest? {
        val request = LocalRepositoryWorktreeRequest()
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath
            } ?: return null
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        operationRequest = null,
                        refreshRequest = request,
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return request
        }
    }

    fun publishDiscovered(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        basicWorktrees: List<LocalWorktreeUiState>,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath &&
                    it.refreshRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        operationRequest = null,
                        worktrees = basicWorktrees.withEnrichmentFrom(currentRepository.worktrees),
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }

    fun complete(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        enrichedWorktrees: List<LocalWorktreeUiState>,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath &&
                    it.refreshRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        operationRequest = null,
                        refreshRequest = null,
                        worktrees = currentRepository.worktrees.withEnrichmentFrom(enrichedWorktrees),
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }

    fun fail(normalizedRepoRootPath: String, request: LocalRepositoryWorktreeRequest): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath &&
                    it.refreshRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        refreshRequest = null,
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }
}
