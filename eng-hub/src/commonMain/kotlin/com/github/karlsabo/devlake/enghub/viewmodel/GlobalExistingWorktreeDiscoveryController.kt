package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal class GlobalExistingWorktreeDiscoveryController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val launchGitHubAction: (suspend (EngHubGitHubServices) -> Unit) -> Job,
) {
    private var branchDiscoveryJob: Job? = null
    private var pullRequestDiscoveryJob: Job? = null

    fun discoverExistingBranches() {
        val repoRootPaths = state.configuredGlobalRepoRootPaths()
        branchDiscoveryJob?.cancel()
        val request = startBranchDiscovery(repoRootPaths)
        if (repoRootPaths.isEmpty()) return

        branchDiscoveryJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            supervisorScope {
                repoRootPaths.forEach { repoRootPath ->
                    launch { discoverRepositoryBranches(request, repoRootPath) }
                }
            }
        }
    }

    fun discoverPullRequests(query: String) {
        val repoRootPaths = state.configuredGlobalRepoRootPaths()
        pullRequestDiscoveryJob?.cancel()
        val normalizedQuery = query.trim()
        val number = plainPullRequestNumber(normalizedQuery)
        if (number == null) {
            clearPullRequests(repoRootPaths, normalizedQuery)
            return
        }

        val request = startPullRequestDiscovery(repoRootPaths, normalizedQuery)
        if (repoRootPaths.isEmpty()) return

        pullRequestDiscoveryJob = launchGitHubAction { services ->
            supervisorScope {
                repoRootPaths.forEach { repoRootPath ->
                    launch { discoverRepositoryPullRequest(request, repoRootPath, number, services) }
                }
            }
        }
    }

    private suspend fun discoverRepositoryBranches(
        request: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
    ) {
        runCatching { worktreeServices.gitWorktreeApi.refreshAndListExistingBranches(repoRootPath) }
            .rethrowCancellation()
            .onSuccess { branches ->
                finishBranchDiscovery(
                    request = request,
                    repoRootPath = repoRootPath,
                    branches = branches.branches,
                    originBranches = branches.originBranches,
                    originBranchRefreshSucceeded = branches.originBranchRefreshSucceeded,
                )
            }
            .onFailure { failure ->
                logger.error(failure) { "Failed to discover existing branches for $repoRootPath" }
                finishBranchDiscovery(
                    request = request,
                    repoRootPath = repoRootPath,
                    branches = emptyList(),
                    originBranches = emptyList(),
                    originBranchRefreshSucceeded = false,
                )
            }
    }

    private suspend fun discoverRepositoryPullRequest(
        request: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
        number: Int,
        services: EngHubGitHubServices,
    ) {
        runCatching {
            discoverPullRequestWorktreeCandidate(
                repoRootPath = repoRootPath,
                number = number,
                gitWorktreeApi = worktreeServices.gitWorktreeApi,
                services = services,
            )
        }.rethrowCancellation()
            .onSuccess { outcome -> finishPullRequestDiscovery(request, repoRootPath, outcome) }
            .onFailure { failure ->
                logger.error(failure) { "Failed to discover pull request #$number for $repoRootPath" }
                finishPullRequestDiscovery(request, repoRootPath, PullRequestDiscoveryOutcome.NoResult)
            }
    }

    private fun startBranchDiscovery(
        repoRootPaths: List<String>,
    ): GlobalExistingBranchDiscoveryState {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            val requestId = current.requestId + 1
            val repositories = loadingBranchRepositories(repoRootPaths, current.repositories, requestId)
            val request = current.copy(
                repoRootPaths = repoRootPaths,
                repositories = repositories,
                isLoading = repositories.isNotEmpty(),
                requestId = requestId,
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, request)) return request
        }
    }

    private fun loadingBranchRepositories(
        repoRootPaths: List<String>,
        currentRepositories: Map<String, ExistingBranchDiscoveryState>,
        requestId: Long,
    ): Map<String, ExistingBranchDiscoveryState> = repoRootPaths.associateWith { repoRootPath ->
        currentRepositories[repoRootPath].orEmptyRepository(repoRootPath).copy(
            originBranchRefreshSucceeded = null,
            isLoading = true,
            requestId = requestId,
        )
    }

    private fun startPullRequestDiscovery(
        repoRootPaths: List<String>,
        query: String,
    ): GlobalExistingBranchDiscoveryState {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            val requestId = current.pullRequestRequestId + 1
            val repositories = repoRootPaths.associateWith { repoRootPath ->
                current.repositories[repoRootPath].orEmptyRepository(repoRootPath).copy(
                    pullRequestQuery = query,
                    pullRequest = null,
                    isPullRequestLoading = true,
                    pullRequestRequestId = requestId,
                    unsupportedPullRequestMessage = null,
                )
            }
            val request = current.copy(
                repoRootPaths = repoRootPaths,
                repositories = repositories,
                pullRequestRequestId = requestId,
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, request)) return request
        }
    }

    private fun clearPullRequests(
        repoRootPaths: List<String>,
        query: String,
    ) {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            val requestId = current.pullRequestRequestId + 1
            val repositories = repoRootPaths.associateWith { repoRootPath ->
                current.repositories[repoRootPath].orEmptyRepository(repoRootPath).copy(
                    pullRequestQuery = query,
                    pullRequest = null,
                    isPullRequestLoading = false,
                    pullRequestRequestId = requestId,
                    unsupportedPullRequestMessage = null,
                )
            }
            val cleared = current.copy(
                repoRootPaths = repoRootPaths,
                repositories = repositories,
                pullRequestRequestId = requestId,
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, cleared)) return
        }
    }

    private fun finishBranchDiscovery(
        request: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
        branches: List<String>,
        originBranches: List<String>,
        originBranchRefreshSucceeded: Boolean,
    ) {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            if (current.requestId != request.requestId || repoRootPath !in current.repoRootPaths) return
            val repository = current.repositories.getValue(repoRootPath).copy(
                branches = branches,
                originBranches = originBranches,
                originBranchRefreshSucceeded = originBranchRefreshSucceeded,
                isLoading = false,
            )
            val repositories = current.repositories + (repoRootPath to repository)
            val completed = current.copy(
                repositories = repositories,
                isLoading = repositories.values.any { it.isLoading },
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, completed)) return
        }
    }

    private fun finishPullRequestDiscovery(
        request: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
        outcome: PullRequestDiscoveryOutcome,
    ) {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            if (
                current.pullRequestRequestId != request.pullRequestRequestId ||
                repoRootPath !in current.repoRootPaths
            ) {
                return
            }
            val repository = current.repositories.getValue(repoRootPath).copy(
                pullRequest = (outcome as? PullRequestDiscoveryOutcome.Candidate)?.value,
                isPullRequestLoading = false,
                unsupportedPullRequestMessage = if (outcome == PullRequestDiscoveryOutcome.UnsupportedFork) {
                    "Fork pull requests are not supported."
                } else {
                    null
                },
            )
            val completed = current.copy(repositories = current.repositories + (repoRootPath to repository))
            if (state.globalExistingBranchDiscovery.compareAndSet(current, completed)) return
        }
    }
}

private fun EngHubViewModelState.configuredGlobalRepoRootPaths(): List<String> = currentConfig.localRepositories
    .map { it.path }
    .filter(String::isNotBlank)
    .distinct()

private fun ExistingBranchDiscoveryState?.orEmptyRepository(
    repoRootPath: String,
): ExistingBranchDiscoveryState = this ?: ExistingBranchDiscoveryState(repoRootPath = repoRootPath)
