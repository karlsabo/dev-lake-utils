package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.configuredWorktreeSetupCommands
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupHandle
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CheckoutController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val errorReporter: ActionErrorReporter,
) {
    fun checkoutAndOpen(repoFullName: String, branch: String): Job {
        val setupRequestResult = runCatching {
            val worktreePath = checkoutWorktreePath(repoFullName, branch)
            worktreePath to requestCheckoutSetup(repoFullName, branch)
        }
        return viewModel.viewModelScope.launch(Dispatchers.IO) {
            setupRequestResult
                .mapCatching { (_, setupHandle) ->
                    setupHandle.await()
                    logger.info { "Setup: done" }
                }
                .onFailure { failure ->
                    val message = failure.message ?: "Failed to set up worktree"
                    val shouldReport = setupRequestResult.getOrNull()?.let { (worktreePath, handle) ->
                        errorReporter.enqueueSetupActionErrorOnce(worktreePath, handle, message)
                    } ?: run {
                        errorReporter.enqueueActionError(message)
                        true
                    }
                    if (shouldReport) {
                        logger.error(failure) { "Failed to set up $repoFullName branch=$branch" }
                    }
                }
        }
    }

    fun requestCheckoutSetup(repoFullName: String, branch: String): WorktreeSetupHandle {
        val activeConfig = state.currentConfig
        val repoPath = checkoutRepoPath(repoFullName, activeConfig)
        val worktreePath = buildWorktreePath(repoPath, branch)
        logger.info { "Setup: requesting checkout setup for $repoFullName branch=$branch at $worktreePath" }
        return worktreeServices.worktreeSetupCoordinator.setup(
            WorktreeSetupRequest(
                repoPath = repoPath,
                worktreePath = worktreePath,
                cloneUrl = "https://github.com/$repoFullName.git",
                branch = branch,
                setupShell = activeConfig.setupShell,
                setupCommands = configuredWorktreeSetupCommands(repoPath, activeConfig),
            ),
        )
    }

    fun checkoutWorktreePath(repoFullName: String, branch: String): WorktreePath {
        val repoPath = checkoutRepoPath(repoFullName, state.currentConfig)
        return buildWorktreePath(repoPath, branch)
    }
}
