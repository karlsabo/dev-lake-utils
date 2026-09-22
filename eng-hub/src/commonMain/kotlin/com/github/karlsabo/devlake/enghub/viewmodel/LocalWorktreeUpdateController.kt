package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitMergeConflictException
import com.github.karlsabo.git.GitRebaseConflictException
import com.github.karlsabo.git.WorktreeUnchangedFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeUpdateController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    fun updateLocalWorktreeFromOrigin(
        repoRootPath: String,
        worktreePath: String,
        branch: String,
    ) = updateLocalWorktree(
        repoRootPath = repoRootPath,
        worktreePath = worktreePath,
        sourceBranch = branch,
        sourceDescription = "origin",
    ) {
        worktreeServices.gitWorktreeApi.updateWorktreeFromOrigin(worktreePath, branch)
    }

    fun updateLocalWorktreeFromParent(
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) = updateLocalWorktree(
        repoRootPath = repoRootPath,
        worktreePath = worktreePath,
        sourceBranch = parentBranch,
        sourceDescription = "parent",
    ) {
        worktreeServices.gitWorktreeApi.updateWorktreeFromParent(worktreePath, parentBranch)
    }

    private fun updateLocalWorktree(
        repoRootPath: String,
        worktreePath: String,
        sourceBranch: String,
        sourceDescription: String,
        update: () -> Unit,
    ) {
        val worktreeIdentity = worktreePath.normalizedRepositoryPath()
        if (repoRootPath.isBlank() || worktreeIdentity.isEmpty() || sourceBranch.isBlank()) return
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
        state.integratingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
        state.updatingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
        val finishUpdate = {
            state.updatingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            state.integratingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            mutationLease.release()
        }

        val updateJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            var conflictRequest: WorktreeConflictResolutionRequest? = null
            try {
                val failure = runCatching {
                    logger.info { "Updating worktree $worktreePath from $sourceDescription $sourceBranch" }
                    update()
                }
                    .rethrowCancellation()
                    .exceptionOrNull()
                if (failure != null) {
                    logger.error(failure) {
                        "Failed to update worktree $worktreePath from $sourceDescription $sourceBranch"
                    }
                    conflictRequest = conflictRequestFor(failure, repoRootPath, worktreePath, sourceBranch)
                    if (conflictRequest == null) {
                        errorReporter.enqueueActionError(
                            failure.message ?: "Failed to update worktree from $sourceDescription",
                        )
                    }
                }
                if (failure !is WorktreeUnchangedFailure) {
                    localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                        repoRootPath = repoRootPath,
                        logContext = "after update from $sourceDescription",
                    )
                }
            } finally {
                finishUpdate()
            }
            conflictRequest?.let { request ->
                enqueueWorktreeConflictResolutionRequest(state.worktreeConflictResolutionRequests, request)
            }
        }
        updateJob.invokeOnCompletion { finishUpdate() }
    }

    private fun conflictRequestFor(
        failure: Throwable,
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ): WorktreeConflictResolutionRequest? {
        val operation = when (failure) {
            is GitRebaseConflictException -> WorktreeIntegrationOperation.Rebase
            is GitMergeConflictException -> WorktreeIntegrationOperation.Merge
            else -> return null
        }
        return WorktreeConflictResolutionRequest(operation, repoRootPath, worktreePath, parentBranch)
    }
}
