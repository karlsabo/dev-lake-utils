package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.configuredWorktreeSetupCommands
import com.github.karlsabo.git.ExistingTargetBranchAncestryException
import com.github.karlsabo.git.WorktreeBranchNameValidator
import com.github.karlsabo.git.WorktreeSetupHandle
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class LocalWorktreeFromBaseCreator(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    fun createLocalWorktreeFromBase(request: CreateLocalWorktreeFromBaseRequest) {
        logger.info {
            "Create local worktree requested for ${request.repoRootPath} " +
                "base=${request.baseBranch} baseCommitIsh=${request.baseCommitIsh} " +
                "target=${request.targetBranch} allowUnrelatedExistingBranch=${request.allowUnrelatedExistingBranch}"
        }
        state.lastCreateLocalWorktreeFromBaseRequest.value = request
        if (!request.allowUnrelatedExistingBranch) {
            state.useUnrelatedExistingBranchConfirmationRequest.value = null
        }

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val refresh = LocalWorktreeCreateRefresh()
            runCatching { createLocalWorktree(request, refresh) }
                .rethrowCancellation()
                .onFailure { failure -> handleCreateFailure(request, refresh, failure) }
            refresh.refreshAfterSuccessIfNeeded()
        }
    }

    private suspend fun createLocalWorktree(
        request: CreateLocalWorktreeFromBaseRequest,
        refresh: LocalWorktreeCreateRefresh,
    ) {
        val setupRequest = buildCreateLocalWorktreeFromBaseSetupRequest(request)
        val setupHandle = worktreeServices.worktreeSetupCoordinator.setup(setupRequest)
        refresh.setupRequest = setupRequest
        refresh.setupHandle = setupHandle
        refresh.postCreateRefresh = launchPostCreateLocalWorktreeRefresh(setupRequest)
        setupHandle.await()
        logger.info { "Setup: created local worktree setup done for ${setupRequest.worktreePath.value}" }
    }

    private fun handleCreateFailure(
        request: CreateLocalWorktreeFromBaseRequest,
        refresh: LocalWorktreeCreateRefresh,
        failure: Throwable,
    ) {
        if (!request.allowUnrelatedExistingBranch && failure is ExistingTargetBranchAncestryException) {
            refresh.refreshedAfterFailure = true
            state.useUnrelatedExistingBranchConfirmationRequest.value = request.toConfirmation()
            logger.info {
                "Confirmation required to create local worktree for unrelated existing branch " +
                    "${request.targetBranch} from ${request.baseBranch}"
            }
            return
        }

        val message = failure.message ?: "Failed to create worktree"
        val shouldReport = refresh.setupRequest?.let { requestWithPath ->
            refresh.setupHandle?.let { handle ->
                errorReporter.enqueueSetupActionErrorOnce(requestWithPath.worktreePath, handle, message)
            }
        } ?: run {
            errorReporter.enqueueActionError(message)
            true
        }
        if (shouldReport) {
            logger.error(failure) {
                "Failed to create local worktree for ${request.repoRootPath} " +
                    "base=${request.baseBranch} target=${request.targetBranch}"
            }
        }
        refresh.setupRequest?.let { failedSetupRequest ->
            refresh.refreshedAfterFailure = true
            refreshLocalRepositoryWorktreesAfterSetupFailure(failedSetupRequest)
        }
    }

    private fun buildCreateLocalWorktreeFromBaseSetupRequest(
        request: CreateLocalWorktreeFromBaseRequest,
    ): WorktreeSetupRequest {
        val normalizedRepoRootPath = request.repoRootPath.normalizedRepoPath()
        val normalizedBaseWorktreePath = request.baseWorktreePath.normalizedRepoPath()
        validateCreateLocalWorktreeFromBaseRequest(
            repoRootPath = normalizedRepoRootPath,
            baseWorktreePath = normalizedBaseWorktreePath,
            baseBranch = request.baseBranch,
            targetBranch = request.targetBranch,
            baseCommitIsh = request.baseCommitIsh,
        )

        val worktreePath = buildWorktreePath(normalizedRepoRootPath, request.targetBranch)
        return WorktreeSetupRequest(
            repoPath = normalizedRepoRootPath,
            worktreePath = worktreePath,
            baseWorktreePath = normalizedBaseWorktreePath,
            baseBranch = request.baseBranch.takeIf { request.baseCommitIsh == null },
            baseCommitIsh = request.baseCommitIsh,
            targetBranch = request.targetBranch,
            allowUnrelatedExistingBranch = request.allowUnrelatedExistingBranch,
            setupShell = state.currentConfig.setupShell,
            setupCommands = configuredWorktreeSetupCommands(normalizedRepoRootPath, state.currentConfig),
        )
    }

    private fun CreateLocalWorktreeFromBaseRequest.toConfirmation() = UseUnrelatedExistingBranchConfirmationRequest(
        repoRootPath = repoRootPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
    )

    private fun validateCreateLocalWorktreeFromBaseRequest(
        repoRootPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        baseCommitIsh: String?,
    ) {
        require(repoRootPath.isNotEmpty()) { "Repository root path is required" }
        require(baseWorktreePath.isNotEmpty()) { "Base worktree path is required" }
        require(baseBranch.isNotBlank()) { "Base branch label is required" }
        baseCommitIsh?.let { commitIsh ->
            require(commitIsh.isNotBlank()) { "Base commit-ish is required" }
            require(commitIsh != targetBranch) { "Target branch must differ from the base commit-ish" }
        } ?: require(baseBranch != targetBranch) { "Target branch must differ from the base branch" }

        val branchNameValidator = WorktreeBranchNameValidator()
        if (baseCommitIsh == null) {
            val baseBranchValidation = branchNameValidator.validate(baseBranch)
            require(baseBranchValidation.isValid) { "Invalid base branch: ${baseBranchValidation.message}" }
        }
        val targetBranchValidation = branchNameValidator.validate(targetBranch)
        require(targetBranchValidation.isValid) { "Invalid target branch: ${targetBranchValidation.message}" }
    }

    private fun launchPostCreateLocalWorktreeRefresh(
        setupRequest: WorktreeSetupRequest,
    ): PostCreateLocalWorktreeRefresh? {
        if (setupRequest.setupCommands.isEmpty()) return null

        val refreshState = PostCreateLocalWorktreeRefreshState()
        val job = viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                state.setupStatusesStateFlow.first { statuses ->
                    statuses[setupRequest.worktreePath] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
                }
                if (refreshState.markRefreshStarted()) refreshLocalRepositoryWorktreesAfterCreate(setupRequest)
            }
                .rethrowCancellation()
                .onFailure { failure ->
                    logger.error(failure) {
                        "Failed to refresh worktrees after creating ${setupRequest.worktreePath.value}"
                    }
                }
        }
        return PostCreateLocalWorktreeRefresh(job, refreshState)
    }

    private fun refreshLocalRepositoryWorktreesAfterCreate(setupRequest: WorktreeSetupRequest) {
        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = setupRequest.repoPath,
            logContext = "after creating worktree ${setupRequest.worktreePath.value}",
        )
    }

    private fun refreshLocalRepositoryWorktreesAfterSetupFailure(setupRequest: WorktreeSetupRequest) {
        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = setupRequest.repoPath,
            logContext = "after setup failed for worktree ${setupRequest.worktreePath.value}",
        )
    }

    private inner class LocalWorktreeCreateRefresh {
        var setupRequest: WorktreeSetupRequest? = null
        var setupHandle: WorktreeSetupHandle? = null
        var postCreateRefresh: PostCreateLocalWorktreeRefresh? = null
        var refreshedAfterFailure = false

        suspend fun refreshAfterSuccessIfNeeded() {
            val refreshStarted = postCreateRefresh?.cancelWaitingOrAwaitStartedRefresh() ?: false
            val submittedSetupRequest = setupRequest
            if (!refreshStarted && submittedSetupRequest != null && !refreshedAfterFailure) {
                refreshLocalRepositoryWorktreesAfterCreate(submittedSetupRequest)
            }
        }
    }
}
