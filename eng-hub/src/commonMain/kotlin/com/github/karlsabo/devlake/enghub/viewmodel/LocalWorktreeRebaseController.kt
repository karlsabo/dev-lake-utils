package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitRebaseConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeRebaseController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    fun rebaseLocalWorktreeOntoParent(
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        val worktreeIdentity = worktreePath.normalizedRepositoryPath()
        if (repoRootPath.isBlank() || worktreeIdentity.isEmpty()) return
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
        state.integratingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
        state.rebasingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }

        val rebaseJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            var conflictFailure: GitRebaseConflictException? = null
            try {
                runCatching {
                    require(parentBranch.isNotBlank()) { "Parent branch is required" }
                    logger.info { "Rebasing worktree $worktreePath for $repoRootPath onto $parentBranch" }
                    worktreeServices.gitWorktreeApi.rebaseWorktreeOntoParent(worktreePath, parentBranch)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = repoRootPath,
                            logContext = "after rebase",
                        )
                    }
                    .onFailure { failure ->
                        if (failure is GitRebaseConflictException) {
                            conflictFailure = failure
                        } else {
                            handleRebaseFailure(failure, repoRootPath, worktreePath, parentBranch)
                        }
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                state.integratingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                mutationLease.release()
            }
            conflictFailure?.let { failure ->
                handleRebaseFailure(failure, repoRootPath, worktreePath, parentBranch)
            }
        }
        rebaseJob.invokeOnCompletion { mutationLease.release() }
    }

    fun abortRebaseAfterConflict(request: WorktreeConflictResolutionRequest) {
        val worktreeIdentity = request.worktreePath.normalizedRepositoryPath()
        if (request.operation != WorktreeIntegrationOperation.Rebase ||
            !canAbortRebaseAfterConflict(request, request.repoRootPath, worktreeIdentity)
        ) {
            return
        }
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(request.worktreePath) ?: return

        val abortJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            state.rebasingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
            try {
                runCatching {
                    logger.info { "Aborting conflicted rebase in worktree ${request.worktreePath}" }
                    worktreeServices.gitWorktreeApi.abortRebase(request.worktreePath)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        clearWorktreeConflictResolutionRequest(state.worktreeConflictResolutionRequests, request)
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after aborting rebase",
                        )
                    }
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to abort rebase in worktree ${request.worktreePath}" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to abort rebase")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after abort rebase failure",
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                mutationLease.release()
            }
        }
        abortJob.invokeOnCompletion { mutationLease.release() }
    }

    private fun handleRebaseFailure(
        failure: Throwable,
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        logger.error(failure) { "Failed to rebase worktree $worktreePath onto $parentBranch" }
        if (failure is GitRebaseConflictException) {
            enqueueWorktreeConflictResolutionRequest(
                state.worktreeConflictResolutionRequests,
                WorktreeConflictResolutionRequest(
                    operation = WorktreeIntegrationOperation.Rebase,
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

    private fun canAbortRebaseAfterConflict(
        request: WorktreeConflictResolutionRequest,
        repoRootPath: String,
        worktreePath: String,
    ): Boolean = repoRootPath.isNotEmpty() &&
        worktreePath.isNotEmpty() &&
        hasWorktreeConflictResolutionRequest(state.worktreeConflictResolutionRequests, request)
}
