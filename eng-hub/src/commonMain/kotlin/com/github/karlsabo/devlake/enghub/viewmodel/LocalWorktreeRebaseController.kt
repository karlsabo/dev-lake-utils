package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                        logger.error(failure) { "Failed to rebase worktree $normalizedWorktreePath onto $parentBranch" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to rebase worktree")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = normalizedRepoRootPath,
                            logContext = "after rebase failure",
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
            }
        }
    }
}
