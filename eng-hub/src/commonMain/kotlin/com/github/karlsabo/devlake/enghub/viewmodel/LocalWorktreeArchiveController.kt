package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_WORKTREE_REMOVAL_WAIT_TIMEOUT = 30.seconds

internal class LocalWorktreeArchiveController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val worktreeServices: EngHubWorktreeServices,
    private val localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
    private val worktreeRemovalWaitTimeout: Duration = DEFAULT_WORKTREE_REMOVAL_WAIT_TIMEOUT,
) {
    fun archiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        archiveLocalWorktree(repoRootPath, worktreePath, force = false)
    }

    fun confirmForceArchiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        val request = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
        if (state.forceArchiveWorktreeRequest.compareAndSet(expect = request, update = null)) {
            archiveLocalWorktree(repoRootPath, worktreePath, force = true)
        }
    }

    fun dismissForceArchiveWorktreeRequest() {
        state.forceArchiveWorktreeRequest.value = null
    }

    private fun archiveLocalWorktree(
        repoRootPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepositoryPath()
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        when {
            normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty() -> Unit

            normalizedRepoRootPath == normalizedWorktreePath -> {
                errorReporter.enqueueActionError("Cannot archive root worktree: $worktreePath")
            }

            else -> {
                val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
                state.archivingLocalWorktreePaths.update { paths -> paths + normalizedWorktreePath }
                launchArchive(repoRootPath, worktreePath, normalizedWorktreePath, force, mutationLease)
            }
        }
    }

    private fun launchArchive(
        repoRootPath: String,
        worktreePath: String,
        normalizedWorktreePath: String,
        force: Boolean,
        mutationLease: LocalWorktreeMutationGuard.Lease,
    ) {
        val archiveJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            val failure = try {
                runCatching {
                    logger.info { "Archiving worktree $worktreePath for $repoRootPath force=$force" }
                    worktreeServices.gitWorktreeApi.archiveWorktree(repoRootPath, worktreePath, force = force)
                }
                    .rethrowCancellation()
                    .exceptionOrNull()
                    .also { archiveFailure ->
                        if (archiveFailure == null) {
                            localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                                repoRootPath = repoRootPath,
                                logContext = "after archive",
                            )
                            awaitWorktreeRemoval(repoRootPath, normalizedWorktreePath)
                        }
                    }
            } finally {
                state.archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
                mutationLease.release()
            }

            failure?.let { archiveFailure ->
                logger.error(archiveFailure) { "Failed to archive worktree $worktreePath" }
                if (!force && archiveFailure.isDirtyWorktreeArchiveFailure()) {
                    state.forceArchiveWorktreeRequest.value = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
                } else {
                    errorReporter.enqueueActionError(archiveFailure.message ?: "Failed to archive worktree")
                }
                localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                    repoRootPath = repoRootPath,
                    logContext = "after archive failure",
                )
            }
        }
        archiveJob.invokeOnCompletion { mutationLease.release() }
    }

    private suspend fun awaitWorktreeRemoval(
        repoRootPath: String,
        worktreePath: String,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepositoryPath()
        val removalObserved = withTimeoutOrNull(worktreeRemovalWaitTimeout) {
            state.localRepositories.first { repositories ->
                val repository = repositories.firstOrNull {
                    it.path.normalizedRepositoryPath() == normalizedRepoRootPath
                }
                repository == null ||
                    repository.worktrees.none { it.path.normalizedRepositoryPath() == worktreePath }
            }
            true
        } == true
        if (!removalObserved) {
            logger.warn {
                "Stopped guarding archived worktree $worktreePath after state reconciliation timed out"
            }
        }
    }
}
