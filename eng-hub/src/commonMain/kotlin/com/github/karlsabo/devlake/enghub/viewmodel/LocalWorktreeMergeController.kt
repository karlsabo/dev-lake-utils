package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
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
        if (!state.mergingLocalWorktreePaths.addPathIfAbsent(worktreeIdentity)) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
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
                        logger.error(failure) { "Failed to merge $parentBranch into worktree $worktreePath" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to merge worktree")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = repoRootPath,
                            logContext = "after merge failure",
                        )
                    }
            } finally {
                state.mergingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            }
        }
    }
}
