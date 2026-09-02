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
) {
    private var discoveryJob: Job? = null

    fun discoverExistingBranches() {
        val repoRootPaths = state.currentConfig.localRepositories
            .map { it.path }
            .filter(String::isNotBlank)
            .distinct()
        discoveryJob?.cancel()
        val request = startDiscovery(repoRootPaths)
        if (repoRootPaths.isEmpty()) return

        discoveryJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            supervisorScope {
                repoRootPaths.forEach { repoRootPath ->
                    launch { discoverRepositoryBranches(request, repoRootPath) }
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
                finishDiscovery(
                    request = request,
                    repoRootPath = repoRootPath,
                    branches = branches.branches,
                    originBranches = branches.originBranches,
                    originBranchRefreshSucceeded = branches.originBranchRefreshSucceeded,
                )
            }
            .onFailure { failure ->
                logger.error(failure) { "Failed to discover existing branches for $repoRootPath" }
                finishDiscovery(
                    request = request,
                    repoRootPath = repoRootPath,
                    branches = emptyList(),
                    originBranches = emptyList(),
                    originBranchRefreshSucceeded = false,
                )
            }
    }

    private fun startDiscovery(
        repoRootPaths: List<String>,
    ): GlobalExistingBranchDiscoveryState {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            val requestId = current.requestId + 1
            val repositories = loadingRepositories(repoRootPaths, requestId)
            val request = GlobalExistingBranchDiscoveryState(
                repoRootPaths = repoRootPaths,
                repositories = repositories,
                isLoading = repositories.isNotEmpty(),
                requestId = requestId,
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, request)) return request
        }
    }

    private fun loadingRepositories(
        repoRootPaths: List<String>,
        requestId: Long,
    ): Map<String, ExistingBranchDiscoveryState> = repoRootPaths.associateWith { repoRootPath ->
        ExistingBranchDiscoveryState(
            repoRootPath = repoRootPath,
            originBranchRefreshSucceeded = null,
            isLoading = true,
            requestId = requestId,
        )
    }

    private fun finishDiscovery(
        request: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
        branches: List<String>,
        originBranches: List<String>,
        originBranchRefreshSucceeded: Boolean,
    ) {
        while (true) {
            val current = state.globalExistingBranchDiscovery.value
            if (current.requestId != request.requestId || repoRootPath !in current.repoRootPaths) return
            val repositories = updateCompletedRepository(
                state = current,
                repoRootPath = repoRootPath,
                branches = branches,
                originBranches = originBranches,
                originBranchRefreshSucceeded = originBranchRefreshSucceeded,
            )
            val completed = current.copy(
                repositories = repositories,
                isLoading = repositories.values.any { it.isLoading },
            )
            if (state.globalExistingBranchDiscovery.compareAndSet(current, completed)) return
        }
    }

    private fun updateCompletedRepository(
        state: GlobalExistingBranchDiscoveryState,
        repoRootPath: String,
        branches: List<String>,
        originBranches: List<String>,
        originBranchRefreshSucceeded: Boolean,
    ): Map<String, ExistingBranchDiscoveryState> {
        val repository = state.repositories.getValue(repoRootPath).copy(
            branches = branches,
            originBranches = originBranches,
            originBranchRefreshSucceeded = originBranchRefreshSucceeded,
            isLoading = false,
        )
        return state.repositories + (repoRootPath to repository)
    }
}
