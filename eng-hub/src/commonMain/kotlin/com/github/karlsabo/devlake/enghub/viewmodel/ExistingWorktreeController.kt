package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.configuredWorktreeSetupCommands
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupHandle
import com.github.karlsabo.git.WorktreeSetupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ExistingWorktreeController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val errorReporter: ActionErrorReporter,
) {
    fun openLocalWorktree(repoRootPath: String, worktreePath: String) {
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        if (normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty()) return

        val worktreeKey = WorktreePath(normalizedWorktreePath)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            var setupHandle: WorktreeSetupHandle? = null
            runCatching {
                logger.info { "Setup: requesting existing worktree setup for $normalizedWorktreePath" }
                setupHandle = requestExistingWorktreeSetup(normalizedRepoRootPath, normalizedWorktreePath)
                setupHandle.await()
                logger.info { "Setup: existing worktree setup done for $normalizedWorktreePath" }
            }.onFailure { failure ->
                val message = failure.message ?: "Failed to set up worktree"
                val shouldReport = setupHandle?.let { handle ->
                    errorReporter.enqueueSetupActionErrorOnce(worktreeKey, handle, message)
                } ?: run {
                    errorReporter.enqueueActionError(message)
                    true
                }
                if (shouldReport) logger.error(failure) { "Failed to set up existing worktree $worktreePath" }
            }
        }
    }

    fun requestExistingWorktreeSetup(repoRootPath: String, worktreePath: String): WorktreeSetupHandle {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        return worktreeServices.worktreeSetupCoordinator.setup(
            WorktreeSetupRequest(
                repoPath = normalizedRepoRootPath,
                worktreePath = WorktreePath(normalizedWorktreePath),
                setupShell = state.currentConfig.setupShell,
                setupCommands = configuredWorktreeSetupCommands(normalizedRepoRootPath, state.currentConfig),
            ),
        )
    }
}
