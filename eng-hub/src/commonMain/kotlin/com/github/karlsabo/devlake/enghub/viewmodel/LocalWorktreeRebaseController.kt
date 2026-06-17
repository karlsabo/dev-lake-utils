package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.git.GitRebaseConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeRebaseController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    private val abortingRebaseWorktreePaths = MutableStateFlow<Set<String>>(emptySet())

    fun rebaseLocalWorktreeOntoParent(
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        if (normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty()) return
        if (!state.rebasingLocalWorktreePaths.addPathIfAbsent(normalizedWorktreePath)) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    require(parentBranch.isNotBlank()) { "Parent branch is required" }
                    logger.info {
                        "Rebasing worktree $normalizedWorktreePath for $normalizedRepoRootPath onto $parentBranch"
                    }
                    worktreeServices.gitWorktreeApi.rebaseWorktreeOntoParent(normalizedWorktreePath, parentBranch)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = normalizedRepoRootPath,
                            logContext = "after rebase",
                        )
                    }
                    .onFailure { failure ->
                        handleRebaseFailure(
                            failure = failure,
                            repoRootPath = normalizedRepoRootPath,
                            worktreePath = normalizedWorktreePath,
                            parentBranch = parentBranch,
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
            }
        }
    }

    fun abortRebaseAfterConflict(request: RebaseConflictResolutionRequest) {
        val normalizedRepoRootPath = request.repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = request.worktreePath.normalizedRepoPath()
        if (!canAbortRebaseAfterConflict(request, normalizedRepoRootPath, normalizedWorktreePath)) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            state.rebasingLocalWorktreePaths.update { paths -> paths + normalizedWorktreePath }
            try {
                runCatching {
                    logger.info { "Aborting conflicted rebase in worktree $normalizedWorktreePath" }
                    worktreeServices.gitWorktreeApi.abortRebase(normalizedWorktreePath)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        clearMatchingConflictRequest(request)
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = normalizedRepoRootPath,
                            logContext = "after aborting rebase",
                        )
                    }
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to abort rebase in worktree $normalizedWorktreePath" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to abort rebase")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = normalizedRepoRootPath,
                            logContext = "after abort rebase failure",
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
                abortingRebaseWorktreePaths.update { paths -> paths - normalizedWorktreePath }
            }
        }
    }

    fun leaveRebaseConflictAsIs(request: RebaseConflictResolutionRequest) {
        clearMatchingConflictRequest(request)
    }

    private fun handleRebaseFailure(
        failure: Throwable,
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        logger.error(failure) { "Failed to rebase worktree $worktreePath onto $parentBranch" }
        if (failure is GitRebaseConflictException) {
            enqueueConflictRequest(
                RebaseConflictResolutionRequest(
                    repoRootPath = repoRootPath,
                    worktreePath = worktreePath,
                    parentBranch = parentBranch,
                ),
            )
        } else {
            errorReporter.enqueueActionError(failure.message ?: "Failed to rebase worktree")
        }
        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = repoRootPath,
            logContext = "after rebase failure",
        )
    }

    private fun enqueueConflictRequest(request: RebaseConflictResolutionRequest) {
        state.rebaseConflictResolutionRequests.update { requests ->
            if (request in requests) requests else requests + request
        }
    }

    private fun canAbortRebaseAfterConflict(
        request: RebaseConflictResolutionRequest,
        repoRootPath: String,
        worktreePath: String,
    ): Boolean = repoRootPath.isNotEmpty() &&
        worktreePath.isNotEmpty() &&
        hasConflictRequest(request) &&
        abortingRebaseWorktreePaths.addPathIfAbsent(worktreePath)

    private fun hasConflictRequest(
        request: RebaseConflictResolutionRequest,
    ): Boolean = request in state.rebaseConflictResolutionRequests.value

    private fun clearMatchingConflictRequest(request: RebaseConflictResolutionRequest): Boolean {
        while (true) {
            val current = state.rebaseConflictResolutionRequests.value
            if (request !in current) return false
            if (state.rebaseConflictResolutionRequests.compareAndSet(current, current - request)) return true
        }
    }
}
