package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitRebaseConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeRebaseController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    private val abortingRebaseWorktreePaths = MutableStateFlow<Set<String>>(emptySet())

    fun rebaseLocalWorktreeOntoParent(
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        val worktreeIdentity = worktreePath.normalizedRepositoryPath()
        if (repoRootPath.isBlank() || worktreeIdentity.isEmpty()) return
        if (!state.rebasingLocalWorktreePaths.addPathIfAbsent(worktreeIdentity)) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching {
                    require(parentBranch.isNotBlank()) { "Parent branch is required" }
                    logger.info { "Rebasing worktree $worktreePath for $repoRootPath onto $parentBranch" }
                    worktreeServices.gitWorktreeApi.rebaseWorktreeOntoParent(worktreePath, parentBranch)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = repoRootPath,
                            logContext = "after rebase",
                        )
                    }
                    .onFailure { failure ->
                        handleRebaseFailure(
                            failure = failure,
                            repoRootPath = repoRootPath,
                            worktreePath = worktreePath,
                            parentBranch = parentBranch,
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
            }
        }
    }

    fun abortRebaseAfterConflict(request: RebaseConflictResolutionRequest) {
        val worktreeIdentity = request.worktreePath.normalizedRepositoryPath()
        if (!canAbortRebaseAfterConflict(request, request.repoRootPath, worktreeIdentity)) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            state.rebasingLocalWorktreePaths.update { paths -> paths + worktreeIdentity }
            try {
                runCatching {
                    logger.info { "Aborting conflicted rebase in worktree ${request.worktreePath}" }
                    worktreeServices.gitWorktreeApi.abortRebase(request.worktreePath)
                }
                    .rethrowCancellation()
                    .onSuccess {
                        clearMatchingConflictRequest(request)
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after aborting rebase",
                        )
                    }
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to abort rebase in worktree ${request.worktreePath}" }
                        errorReporter.enqueueActionError(failure.message ?: "Failed to abort rebase")
                        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                            repoRootPath = request.repoRootPath,
                            logContext = "after abort rebase failure",
                        )
                    }
            } finally {
                state.rebasingLocalWorktreePaths.update { paths -> paths - worktreeIdentity }
                abortingRebaseWorktreePaths.update { paths -> paths - worktreeIdentity }
            }
        }
    }

    fun leaveRebaseConflictAsIs(request: RebaseConflictResolutionRequest) {
        clearMatchingConflictRequest(request)
    }

    private fun handleRebaseFailure(
        failure: Throwable,
        repoRootPath: String,
        worktreePath: String,
        parentBranch: String,
    ) {
        logger.error(failure) { "Failed to rebase worktree $worktreePath onto $parentBranch" }
        if (failure is GitRebaseConflictException) {
            enqueueConflictRequest(
                RebaseConflictResolutionRequest(
                    repoRootPath = repoRootPath,
                    worktreePath = worktreePath,
                    parentBranch = parentBranch,
                ),
            )
        } else {
            errorReporter.enqueueActionError(failure.message ?: "Failed to rebase worktree")
        }
        localRepositories.refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = repoRootPath,
            logContext = "after rebase failure",
        )
    }

    private fun enqueueConflictRequest(request: RebaseConflictResolutionRequest) {
        state.rebaseConflictResolutionRequests.update { requests ->
            if (request in requests) requests else requests + request
        }
    }

    private fun canAbortRebaseAfterConflict(
        request: RebaseConflictResolutionRequest,
        repoRootPath: String,
        worktreePath: String,
    ): Boolean = repoRootPath.isNotEmpty() &&
        worktreePath.isNotEmpty() &&
        hasConflictRequest(request) &&
        abortingRebaseWorktreePaths.addPathIfAbsent(worktreePath)

    private fun hasConflictRequest(
        request: RebaseConflictResolutionRequest,
    ): Boolean = request in state.rebaseConflictResolutionRequests.value

    private fun clearMatchingConflictRequest(request: RebaseConflictResolutionRequest): Boolean {
        while (true) {
            val current = state.rebaseConflictResolutionRequests.value
            if (request !in current) return false
            if (state.rebaseConflictResolutionRequests.compareAndSet(current, current - request)) return true
        }
    }
}
