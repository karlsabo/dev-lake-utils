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
        if (repoRootPath.isBlank() || worktreePath.isBlank()) return

        val worktreeKey = WorktreePath(worktreePath)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            var setupHandle: WorktreeSetupHandle? = null
            runCatching {
                logger.info { "Setup: requesting existing worktree setup for $worktreePath" }
                setupHandle = requestExistingWorktreeSetup(repoRootPath, worktreePath)
                setupHandle.await()
                logger.info { "Setup: existing worktree setup done for $worktreePath" }
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

    fun requestExistingWorktreeSetup(
        repoRootPath: String,
        worktreePath: String,
    ): WorktreeSetupHandle {
        val activeConfig = state.currentConfig
        val setupCommands = configuredWorktreeSetupCommands(repoRootPath, activeConfig)
        requireSetupShellForCommands(activeConfig.setupShell, setupCommands)
        return worktreeServices.worktreeSetupCoordinator.setup(
            WorktreeSetupRequest(
                repoPath = repoRootPath,
                worktreePath = WorktreePath(worktreePath),
                setupShell = activeConfig.setupShell,
                setupCommands = setupCommands,
            ),
        )
    }
}
