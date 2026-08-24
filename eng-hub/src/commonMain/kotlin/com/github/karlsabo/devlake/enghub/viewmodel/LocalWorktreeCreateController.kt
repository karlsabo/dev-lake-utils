package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class LocalWorktreeCreateController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    private val fromBaseCreator = LocalWorktreeFromBaseCreator(
        viewModel = viewModel,
        state = state,
        worktreeServices = worktreeServices,
        localRepositories = localRepositories,
        errorReporter = errorReporter,
    )

    fun requestCreateLocalWorktreeFromRepository(repoRootPath: String) {
        logger.info { "Create local worktree requested for repository $repoRootPath" }
        state.lastCreateLocalWorktreeFromRepositoryRequest.value = null

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveCreateLocalWorktreeRepositoryBase(repoRootPath) }
                .rethrowCancellation()
                .onSuccess { request -> state.lastCreateLocalWorktreeFromRepositoryRequest.value = request }
                .onFailure { failure ->
                    val message = failure.message ?: "Failed to resolve default branch for worktree creation"
                    logger.error(failure) {
                        "Failed to resolve create local worktree base for repository $repoRootPath"
                    }
                    errorReporter.enqueueActionError(message)
                }
        }
    }

    fun clearCreateLocalWorktreeFromRepositoryRequest() {
        state.lastCreateLocalWorktreeFromRepositoryRequest.value = null
    }

    fun createLocalWorktreeFromBase(
        repoRootPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        baseCommitIsh: String? = null,
    ) {
        fromBaseCreator.createLocalWorktreeFromBase(
            request = CreateLocalWorktreeFromBaseRequest(
                repoRootPath = repoRootPath,
                baseWorktreePath = baseWorktreePath,
                baseBranch = baseBranch,
                targetBranch = targetBranch,
                baseCommitIsh = baseCommitIsh,
            ),
        )
    }

    fun confirmUseUnrelatedExistingBranch(request: UseUnrelatedExistingBranchConfirmationRequest) {
        state.useUnrelatedExistingBranchConfirmationRequest.value = null
        fromBaseCreator.createLocalWorktreeFromBase(
            request = CreateLocalWorktreeFromBaseRequest(
                repoRootPath = request.repoRootPath,
                baseWorktreePath = request.baseWorktreePath,
                baseBranch = request.baseBranch,
                targetBranch = request.targetBranch,
                allowUnrelatedExistingBranch = true,
            ),
        )
    }

    fun dismissUseUnrelatedExistingBranchConfirmation() {
        state.useUnrelatedExistingBranchConfirmationRequest.value = null
    }

    private fun resolveCreateLocalWorktreeRepositoryBase(
        repoRootPath: String,
    ): CreateLocalWorktreeFromRepositoryRequest {
        require(repoRootPath.isNotBlank()) { "Repository root path is required" }
        val baseRef = worktreeServices.gitWorktreeApi.inferDefaultBranchRef(repoRootPath)
        require(!baseRef.isNullOrBlank()) {
            "Could not infer default branch for $repoRootPath"
        }
        return CreateLocalWorktreeFromRepositoryRequest(
            repoRootPath = repoRootPath,
            baseWorktreePath = repoRootPath,
            baseBranch = baseRef,
        )
    }
}
