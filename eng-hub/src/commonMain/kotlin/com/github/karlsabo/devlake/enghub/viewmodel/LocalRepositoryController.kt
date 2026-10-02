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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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

    // Outside viewModelScope's job tree so cancelling polling never waits on blocking Git calls.
    // Disposal cancels this scope immediately, independently of discovery's blocking children.
    private val backgroundWorkScope = viewModel.localRepositoryBackgroundScope()
    private val statusScheduler = LocalWorktreeStatusScheduler(backgroundWorkScope, state) { path ->
        gitWorktreeApi.worktreeIsDirty(path)
    }
    private val worktreeEnrichmentScheduler = LocalWorktreeEnrichmentScheduler(
        scope = backgroundWorkScope,
        gitWorktreeApi = gitWorktreeApi,
    )

    private val pollRefreshScheduler = PerRepositoryConflatedTaskQueue<PollRefreshTask>(backgroundWorkScope) { task ->
        runCatching {
            refreshLocalRepositoryWorktrees(task.repoRootPath) {
                viewModel.viewModelScope.coroutineContext.ensureActive()
                task.requestOwner.ensureActive()
            }
        }.onFailure { failure ->
            if (failure !is CancellationException) {
                logger.error(failure) { "Failed to poll worktrees for ${task.repoRootPath}" }
            }
        }
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
        configDrivenPollingFlow(state.config) { config ->
            flow {
                // A config cancels request ownership, not the worker: synchronous Git may ignore
                // cancellation, and replacing that worker would allow unbounded overlapping calls.
                val requestOwner = Job(backgroundWorkScope.coroutineContext[Job])
                try {
                    worktreePollingFlow(
                        configs = flowOf(config),
                        pollImmediately = pollImmediately,
                    ) { refreshConfiguredLocalRepositoryWorktrees(requestOwner) }.collect { emit(it) }
                } finally {
                    requestOwner.cancel()
                }
            }
        }.collect()
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
        val basicWorktrees = repositoryWorktrees.worktrees
            .toLocalWorktreeUiStatesWithUnknownDirtyStatus(rootPath)
        val publishedRepositories = state.localRepositories.updateAndGet { repositories ->
            val enrichmentRequest = LocalRepositoryWorktreeRequest()
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
        val enrichmentRequest = publishedRepositories.firstOrNull {
            repositoryIdentity(it.path) == normalizedRootPath
        }?.refreshRequest ?: return
        hydrateWorktreeStatuses(normalizedRootPath, enrichmentRequest)

        githubIdentityResolver.resolveAndStore(rootPath, normalizedRootPath, enrichmentRequest)
        worktreeEnrichmentScheduler.schedule(
            repoRootPath = rootPath,
            normalizedRepoRootPath = normalizedRootPath,
            request = enrichmentRequest,
            worktrees = basicWorktrees,
        ) { enrichment ->
            refreshTracker.complete(
                normalizedRootPath,
                enrichmentRequest,
                enrichment.getOrNull(),
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

            hydrateWorktreeStatuses(normalizedRepoRootPath, request)

            githubIdentityResolver.resolveAndStore(repoRootPath, normalizedRepoRootPath, request)
            worktreeEnrichmentScheduler.schedule(
                repoRootPath = repoRootPath,
                normalizedRepoRootPath = normalizedRepoRootPath,
                request = request,
                worktrees = basicWorktrees,
            ) { enrichment ->
                enrichment
                    .onSuccess { expansionTracker.complete(normalizedRepoRootPath, request, it) }
                    .onFailure { expansionTracker.complete(normalizedRepoRootPath, request) }
            }
        }
    }

    /** Schedules published checkout identities, not temporary discovery rows. */
    private fun hydrateWorktreeStatuses(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
    ) {
        val publishedRows = state.localRepositories.value.firstOrNull {
            it.path.normalizedRepositoryPath() == normalizedRepoRootPath && it.statusRequest === request
        }?.worktrees ?: return
        statusScheduler.schedule(normalizedRepoRootPath, request, publishedRows)
    }

    private fun refreshConfiguredLocalRepositoryWorktrees(requestOwner: Job) {
        state.currentConfig.localRepositories
            .asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { repositoryIdentity(it) }
            .forEach { repoRootPath ->
                pollRefreshScheduler.schedule(
                    key = repositoryIdentity(repoRootPath),
                    task = PollRefreshTask(repoRootPath, requestOwner),
                )
            }
    }

    /** Returns discovered rows only when this refresh still owns the published repository state. */
    internal fun refreshLocalRepositoryWorktrees(
        repoRootPath: String,
        checkActive: () -> Unit = {},
    ): List<LocalWorktreeUiState>? {
        checkActive()
        val normalizedRepoRootPath = repositoryIdentity(repoRootPath)
        val request = refreshTracker.start(normalizedRepoRootPath) ?: return null
        val basicWorktrees = runCatching {
            gitWorktreeApi.listWorktreeEntries(repoRootPath)
                .toLocalWorktreeUiStatesWithUnknownDirtyStatus(repoRootPath)
        }.onFailure {
            checkActive()
            refreshTracker.fail(normalizedRepoRootPath, request)
        }.getOrThrow()
        checkActive()
        val published = refreshTracker.publishDiscovered(normalizedRepoRootPath, request, basicWorktrees)
        val publishedWorktrees = state.localRepositories.value.firstOrNull {
            published && it.path.normalizedRepositoryPath() == normalizedRepoRootPath && it.statusRequest === request
        }?.worktrees

        if (publishedWorktrees != null) {
            hydrateWorktreeStatuses(normalizedRepoRootPath, request)

            checkActive()
            githubIdentityResolver.resolveAndStore(repoRootPath, normalizedRepoRootPath, request)
            checkActive()
            worktreeEnrichmentScheduler.schedule(
                repoRootPath = repoRootPath,
                normalizedRepoRootPath = normalizedRepoRootPath,
                request = request,
                worktrees = publishedWorktrees,
            ) { enrichment ->
                refreshTracker.complete(
                    normalizedRepoRootPath,
                    request,
                    enrichment.getOrNull(),
                )
            }
        }
        return basicWorktrees.takeIf {
            publishedWorktrees != null && state.localRepositories.value.any { repository ->
                repository.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    repository.statusRequest === request &&
                    (repository.refreshRequest == null || repository.refreshRequest === request)
            }
        }
    }
}

private data class PollRefreshTask(
    val repoRootPath: String,
    val requestOwner: Job,
)

internal class LocalRepositoryGitHubIdentityResolver(
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val repositoryIdentity: (String) -> String,
) {
    fun resolveAndStore(
        repoRootPath: String,
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
    ) {
        read(repoRootPath).onSuccess { githubIdentity ->
            state.localRepositories.update { repositories ->
                repositories.map { repository ->
                    // Identity belongs to the request, not to retained checkout metadata across polls.
                    if (repositoryIdentity(repository.path) == normalizedRepoRootPath &&
                        (repository.operationRequest === request || repository.refreshRequest === request)
                    ) {
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
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath
            } ?: return null
            val request = LocalRepositoryWorktreeRequest()
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
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    it.refreshRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        operationRequest = null,
                        statusRequest = request,
                        worktrees = basicWorktrees.withEnrichmentFrom(
                            currentRepository.worktrees,
                            preserveCheckout = true,
                        ),
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
        enrichment: LocalWorktreeEnrichment?,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath
            }?.takeIf { current ->
                current.refreshRequest === request ||
                    (
                        current.statusRequest != null && enrichment?.worktrees?.any { enriched ->
                            current.worktrees.any { it.checkout === enriched.checkout }
                        } == true
                        )
            } ?: return false
            val ownsRequest = repository.refreshRequest === request
            val applicableEnrichment = if (ownsRequest) {
                enrichment
            } else {
                enrichment?.retaining(
                    enrichment.worktrees.filter { enriched ->
                        repository.worktrees.any { it.checkout === enriched.checkout }
                    },
                )
            }
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = if (ownsRequest) false else currentRepository.isLoading,
                        operationRequest = if (ownsRequest) null else currentRepository.operationRequest,
                        refreshRequest = if (ownsRequest) null else currentRepository.refreshRequest,
                        worktrees = applicableEnrichment?.let {
                            it.mergeInto(currentRepository.worktrees)
                        } ?: currentRepository.worktrees,
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
