package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
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
    ) {
        val worktreeIdentity = worktreePath.normalizedRepositoryPath()
        if (repoRootPath.isBlank() || worktreeIdentity.isEmpty() || branch.isBlank()) return
        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
        state.integratingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
        state.updatingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }

        val updateJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching {
                logger.info { "Updating $branch in worktree $worktreePath from origin" }
                worktreeServices.gitWorktreeApi.updateWorktreeFromOrigin(worktreePath, branch)
            }
                .rethrowCancellation()
                .exceptionOrNull()
            if (failure != null) {
                logger.error(failure) { "Failed to update $branch in worktree $worktreePath from origin" }
                errorReporter.enqueueActionError(failure.message ?: "Failed to update worktree from origin")
            }
            if (failure !is WorktreeUnchangedFailure) {
                localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                    repoRootPath = repoRootPath,
                    logContext = "after update from origin",
                )
            }
        }
        updateJob.invokeOnCompletion {
            state.updatingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            state.integratingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            mutationLease.release()
        }
    }
}
