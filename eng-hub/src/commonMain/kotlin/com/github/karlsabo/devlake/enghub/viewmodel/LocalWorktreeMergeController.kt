package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitMergeConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeMergeController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    fun mergeLocalWorktreeWithParent(
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        val worktreeIdentity = worktreePath.normalizedRepositoryPath()
        if (repoRootPath.isBlank() || worktreeIdentity.isEmpty()) return
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
        state.integratingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
        state.mergingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }

        val mergeJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            var conflictFailure: GitMergeConflictException? = null
            try {
                runCatching {
                    require(parentBranch.isNotBlank()) { "Parent branch is required" }
                    logger.info { "Merging $parentBranch into worktree $worktreePath for $repoRootPath" }
                    worktreeServices.gitWorktreeApi.mergeWorktreeWithParent(worktreePath, parentBranch)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = repoRootPath,
                            logContext = "after merge",
                        )
                    }
                    .onFailure { failure ->
                        if (failure is GitMergeConflictException) {
                            conflictFailure = failure
                        } else {
                            handleMergeFailure(failure, repoRootPath, worktreePath, parentBranch)
                        }
                    }
            } finally {
                state.mergingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                state.integratingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                mutationLease.release()
            }
            conflictFailure?.let { failure ->
                handleMergeFailure(failure, repoRootPath, worktreePath, parentBranch)
            }
        }
        mergeJob.invokeOnCompletion { mutationLease.release() }
    }

    fun abortMergeAfterConflict(request: WorktreeConflictResolutionRequest) {
        val worktreeIdentity = request.worktreePath.normalizedRepositoryPath()
        if (request.operation != WorktreeIntegrationOperation.Merge ||
            !canAbortMergeAfterConflict(request, request.repoRootPath, worktreeIdentity)
        ) {
            return
        }
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(request.worktreePath) ?: return

        val abortJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            state.mergingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
            try {
                runCatching {
                    logger.info { "Aborting conflicted merge in worktree ${request.worktreePath}" }
                    worktreeServices.gitWorktreeApi.abortMerge(request.worktreePath)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        clearWorktreeConflictResolutionRequest(state.worktreeConflictResolutionRequests, request)
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after aborting merge",
                        )
                    }
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to abort merge in worktree ${request.worktreePath}" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to abort merge")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after abort merge failure",
                        )
                    }
            } finally {
                state.mergingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                mutationLease.release()
            }
        }
        abortJob.invokeOnCompletion { mutationLease.release() }
    }

    private fun handleMergeFailure(
        failure: Throwable,
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        logger.error(failure) { "Failed to merge $parentBranch into worktree $worktreePath" }
        if (failure is GitMergeConflictException) {
            enqueueWorktreeConflictResolutionRequest(
                state.worktreeConflictResolutionRequests,
                WorktreeConflictResolutionRequest(
                    operation = WorktreeIntegrationOperation.Merge,
                    repoRootPath = repoRootPath,
                    worktreePath = worktreePath,
                    parentBranch = parentBranch,
                ),
            )
        } else {
            errorReporter.enqueueActionError(failure.message ?: "Failed to merge worktree")
        }
        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = repoRootPath,
            logContext = "after merge failure",
        )
    }

    private fun canAbortMergeAfterConflict(
        request: WorktreeConflictResolutionRequest,
        repoRootPath: String,
        worktreePath: String,
    ): Boolean = repoRootPath.isNotEmpty() &&
        worktreePath.isNotEmpty() &&
        hasWorktreeConflictResolutionRequest(state.worktreeConflictResolutionRequests, request)
}
