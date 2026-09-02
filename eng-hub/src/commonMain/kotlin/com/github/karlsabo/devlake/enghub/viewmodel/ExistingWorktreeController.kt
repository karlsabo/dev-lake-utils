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

internal class ExistingWorktreeController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    private var discoveryJob: Job? = null

    fun discoverExistingBranches(repoRootPath: String) {
        if (repoRootPath.isBlank()) return
        discoveryJob?.cancel()
        val request = startExistingBranchDiscovery(repoRootPath)
        discoveryJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { worktreeServices.gitWorktreeApi.refreshAndListExistingBranches(repoRootPath) }
                .rethrowCancellation()
                .onSuccess { branches -> finishExistingBranchDiscovery(request, branches) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to discover existing branches for $repoRootPath" }
                    finishExistingBranchDiscovery(request, emptyList())
                }
        }
    }

    private fun startExistingBranchDiscovery(repoRootPath: String): ExistingBranchDiscoveryState {
        while (true) {
            val current = state.existingBranchDiscovery.value
            val request = ExistingBranchDiscoveryState(
                repoRootPath = repoRootPath,
                isLoading = true,
                requestId = current.requestId + 1,
            )
            if (state.existingBranchDiscovery.compareAndSet(current, request)) return request
        }
    }

    private fun finishExistingBranchDiscovery(
        request: ExistingBranchDiscoveryState,
        branches: List<String>,
    ) {
        state.existingBranchDiscovery.compareAndSet(
            expect = request,
            update = request.copy(branches = branches, isLoading = false),
        )
    }

    fun checkoutExistingBranch(repoRootPath: String, branch: String) {
        if (repoRootPath.isBlank() || branch.isBlank()) return
        val worktreePath = buildWorktreePath(repoRootPath, branch)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            var setupHandle: WorktreeSetupHandle? = null
            runCatching {
                val request = existingBranchSetupRequest(repoRootPath, branch, worktreePath)
                setupHandle = worktreeServices.worktreeSetupCoordinator.setup(request)
                setupHandle.await()
                localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                    repoRootPath = repoRootPath,
                    logContext = "after checking out existing branch $branch",
                )
                logger.info { "Setup: existing branch worktree setup done for ${worktreePath.value}" }
            }.rethrowCancellation().onFailure { failure ->
                localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                    repoRootPath = repoRootPath,
                    logContext = "after existing branch setup failed for $branch",
                )
                reportSetupFailure(worktreePath, setupHandle, failure)
            }
        }
    }
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
            }.rethrowCancellation().onFailure { failure ->
                reportSetupFailure(worktreeKey, setupHandle, failure)
            }
        }
    }

    private fun existingBranchSetupRequest(
        repoRootPath: String,
        branch: String,
        worktreePath: WorktreePath,
    ): WorktreeSetupRequest {
        val activeConfig = state.currentConfig
        val setupCommands = configuredWorktreeSetupCommands(repoRootPath, activeConfig)
        requireSetupShellForCommands(activeConfig.setupShell, setupCommands)
        return WorktreeSetupRequest(
            repoPath = repoRootPath,
            worktreePath = worktreePath,
            existingBranch = branch,
            setupShell = activeConfig.setupShell,
            setupCommands = setupCommands,
        )
    }

    private fun reportSetupFailure(
        worktreePath: WorktreePath,
        setupHandle: WorktreeSetupHandle?,
        failure: Throwable,
    ) {
        val message = failure.message ?: "Failed to set up worktree"
        val shouldReport = setupHandle?.let { handle ->
            errorReporter.enqueueSetupActionErrorOnce(worktreePath, handle, message)
        } ?: run {
            errorReporter.enqueueActionError(message)
            true
        }
        if (shouldReport) logger.error(failure) { "Failed to set up worktree ${worktreePath.value}" }
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
