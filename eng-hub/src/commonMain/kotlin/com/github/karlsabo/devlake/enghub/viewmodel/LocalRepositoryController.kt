package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalRepositoryUiStates
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStatesWithUnknownDirtyStatus
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.github.GitHubRepositoryIdentity
import com.github.karlsabo.github.parseGitHubRepositoryIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalRepositoryController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val errorReporter: ActionErrorReporter,
    private val repositoryIdentity: (String) -> String = { it.normalizedRepositoryPath() },
) {
    private val gitWorktreeApi: GitWorktreeApi = worktreeServices.gitWorktreeApi
    private val githubIdentityResolver = LocalRepositoryGitHubIdentityResolver(
        state = state,
        gitWorktreeApi = gitWorktreeApi,
        repositoryIdentity = repositoryIdentity,
    )
    private val expansionTracker = LocalRepositoryExpansionTracker(state)
    private val refreshTracker = LocalRepositoryRefreshTracker(state)
    private val statusTracker = LocalWorktreeStatusTracker(state)

    // Lives as long as the view model, but outside viewModelScope's job tree: background status and
    // enrichment work must outlive the discovery, refresh, and polling jobs that schedule it, so
    // cancelling those jobs must neither cancel nor wait on in-flight background work. Supervised so
    // one failed job neither cancels its siblings nor surfaces upstream.
    private val backgroundWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worktreeEnrichmentScheduler = LocalWorktreeEnrichmentScheduler(
        scope = backgroundWorkScope,
        gitWorktreeApi = gitWorktreeApi,
    )

    init {
        viewModel.viewModelScope.coroutineContext[Job]?.invokeOnCompletion { backgroundWorkScope.cancel() }
    }

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
        val normalizedRepoRootPath = repositoryIdentity(repoRootPath)
        val repository = state.localRepositories.value.firstOrNull {
            repositoryIdentity(it.path) == normalizedRepoRootPath
        }
        when {
            repository == null -> Unit

            repository.isExpanded -> expansionTracker.collapse(normalizedRepoRootPath)

            else -> expansionTracker.start(normalizedRepoRootPath)?.let { request ->
                expandLocalRepository(repoRootPath, normalizedRepoRootPath, request)
            }
        }
    }

    suspend fun pollConfiguredLocalRepositoryWorktrees(pollImmediately: Boolean = true) {
        worktreePollingFlow(
            configs = state.config,
            pollImmediately = pollImmediately,
            poll = ::refreshConfiguredLocalRepositoryWorktrees,
        ).collect()
    }

    fun refreshLocalRepositoryWorktreesBestEffort(repoRootPath: String, logContext: String) {
        runCatching { refreshLocalRepositoryWorktrees(repoRootPath) }
            .rethrowCancellation()
            .onFailure { failure ->
                logger.error(failure) { "Failed to refresh worktrees $logContext for $repoRootPath" }
            }
    }

    private suspend fun addLocalRepositoryBlocking(selectedPath: String) {
        val repositoryWorktrees = gitWorktreeApi.resolveRepositoryRootEntries(selectedPath)
        val rootPath = repositoryWorktrees.rootPath
        var repositoryAdded = false
        state.updateConfig { currentConfig ->
            val alreadyConfigured = currentConfig.localRepositories.any {
                repositoryIdentity(it.path) == repositoryIdentity(rootPath)
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

        val normalizedRootPath = repositoryIdentity(rootPath)
        val enrichmentRequest = LocalRepositoryWorktreeRequest()
        val basicWorktrees = repositoryWorktrees.worktrees
            .toLocalWorktreeUiStatesWithUnknownDirtyStatus(rootPath)
        state.localRepositories.update { repositories ->
            state.currentConfig.localRepositories
                .toLocalRepositoryUiStates(initiallyExpanded = false)
                .withPreservedWorktrees(
                    previousRepositories = repositories,
                    updatedRootPath = rootPath,
                    updatedWorktrees = basicWorktrees,
                    expandUpdatedRepository = true,
                ).map { repository ->
                    if (repositoryIdentity(repository.path) == normalizedRootPath) {
                        repository.copy(refreshRequest = enrichmentRequest, statusRequest = enrichmentRequest)
                    } else {
                        repository
                    }
                }
        }
        hydrateWorktreeStatuses(normalizedRootPath, enrichmentRequest, basicWorktrees)

        githubIdentityResolver.resolveAndStore(rootPath, normalizedRootPath)
        worktreeEnrichmentScheduler.schedule(
            repoRootPath = rootPath,
            normalizedRepoRootPath = normalizedRootPath,
            worktrees = basicWorktrees,
        ) { enrichment ->
            refreshTracker.complete(
                normalizedRootPath,
                enrichmentRequest,
                enrichment.getOrElse { basicWorktrees },
            )
        }
    }

    private fun expandLocalRepository(
        repoRootPath: String,
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
    ) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val basicWorktrees = runCatching {
                gitWorktreeApi.listWorktreeEntries(repoRootPath)
                    .toLocalWorktreeUiStatesWithUnknownDirtyStatus(repoRootPath)
            }.rethrowCancellation().getOrElse { failure ->
                logger.error(failure) { "Failed to list worktrees for $repoRootPath" }
                if (expansionTracker.complete(normalizedRepoRootPath, request)) {
                    errorReporter.enqueueActionError(failure.message ?: "Failed to list worktrees")
                }
                return@launch
            }
            if (!expansionTracker.publishDiscovered(normalizedRepoRootPath, request, basicWorktrees)) return@launch

            hydrateWorktreeStatuses(normalizedRepoRootPath, request, basicWorktrees)

            githubIdentityResolver.resolveAndStore(repoRootPath, normalizedRepoRootPath)
            worktreeEnrichmentScheduler.schedule(
                repoRootPath = repoRootPath,
                normalizedRepoRootPath = normalizedRepoRootPath,
                worktrees = basicWorktrees,
            ) { enrichment ->
                enrichment
                    .onSuccess { expansionTracker.complete(normalizedRepoRootPath, request, it) }
                    .onFailure { expansionTracker.complete(normalizedRepoRootPath, request) }
            }
        }
    }

    /**
     * Resolves the unknown dirty status of each published worktree on view-model IO jobs; results apply only
     * while [request] still owns the rows, so a checkout replaced by a newer request never shows stale status.
     */
    private fun hydrateWorktreeStatuses(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        worktrees: List<LocalWorktreeUiState>,
    ) {
        worktrees.forEach { worktree ->
            backgroundWorkScope.launch {
                val isDirty = runCatching { gitWorktreeApi.worktreeIsDirty(worktree.path) }
                    .rethrowCancellation()
                    .getOrElse { failure ->
                        logger.error(failure) { "Failed to check worktree status for ${worktree.path}" }
                        return@launch
                    }
                statusTracker.publish(
                    normalizedRepoRootPath = normalizedRepoRootPath,
                    request = request,
                    worktreePath = worktree.path,
                    branch = worktree.branch,
                    isDirty = isDirty,
                )
            }
        }
    }

    private fun refreshConfiguredLocalRepositoryWorktrees() {
        state.currentConfig.localRepositories
            .asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { repositoryIdentity(it) }
            .forEach { repoRootPath ->
                runCatching { refreshLocalRepositoryWorktrees(repoRootPath) }
                    .rethrowCancellation()
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to poll worktrees for $repoRootPath" }
                    }
            }
    }

    private fun refreshLocalRepositoryWorktrees(repoRootPath: String) {
        val normalizedRepoRootPath = repositoryIdentity(repoRootPath)
        val request = refreshTracker.start(normalizedRepoRootPath) ?: return
        val basicWorktrees = runCatching {
            gitWorktreeApi.listWorktreeEntries(repoRootPath)
                .toLocalWorktreeUiStatesWithUnknownDirtyStatus(repoRootPath)
        }.onFailure {
            refreshTracker.fail(normalizedRepoRootPath, request)
        }.getOrThrow()
        if (!refreshTracker.publishDiscovered(normalizedRepoRootPath, request, basicWorktrees)) return

        hydrateWorktreeStatuses(normalizedRepoRootPath, request, basicWorktrees)

        githubIdentityResolver.resolveAndStore(repoRootPath, normalizedRepoRootPath)
        worktreeEnrichmentScheduler.schedule(
            repoRootPath = repoRootPath,
            normalizedRepoRootPath = normalizedRepoRootPath,
            worktrees = basicWorktrees,
        ) { enrichment ->
            refreshTracker.complete(
                normalizedRepoRootPath,
                request,
                enrichment.getOrElse { basicWorktrees },
            )
        }
    }
}

private class LocalRepositoryGitHubIdentityResolver(
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val repositoryIdentity: (String) -> String,
) {
    fun resolveAndStore(repoRootPath: String, normalizedRepoRootPath: String) {
        read(repoRootPath).onSuccess { githubIdentity ->
            state.localRepositories.update { repositories ->
                repositories.map { repository ->
                    if (repositoryIdentity(repository.path) == normalizedRepoRootPath) {
                        repository.copy(repositoryIdentity = githubIdentity)
                    } else {
                        repository
                    }
                }
            }
        }
    }

    fun read(repoRootPath: String): Result<GitHubRepositoryIdentity?> = runCatching {
        gitWorktreeApi.originUrl(repoRootPath)?.let(::parseGitHubRepositoryIdentity)
    }.rethrowCancellation().onFailure { failure ->
        logger.error(failure) { "Failed to read origin for $repoRootPath" }
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
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath
            } ?: return null
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        operationRequest = null,
                        refreshRequest = request,
                        statusRequest = null,
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
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    it.refreshRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        operationRequest = null,
                        statusRequest = request,
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
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
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
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
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
