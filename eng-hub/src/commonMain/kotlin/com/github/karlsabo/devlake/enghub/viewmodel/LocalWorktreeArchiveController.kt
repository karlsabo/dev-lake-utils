package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        when {
            normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty() -> Unit

            normalizedRepoRootPath == normalizedWorktreePath -> {
                errorReporter.enqueueActionError("Cannot archive root worktree: $worktreePath")
            }

            state.archivingLocalWorktreePaths.addPathIfAbsent(normalizedWorktreePath) -> launchArchive(
                repoRootPath,
                worktreePath,
                normalizedWorktreePath,
                force,
            )
        }
    }

    private fun launchArchive(
        repoRootPath: String,
        worktreePath: String,
        normalizedWorktreePath: String,
        force: Boolean,
    ) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                logger.info { "Archiving worktree $worktreePath for $repoRootPath force=$force" }
                worktreeServices.gitWorktreeApi.archiveWorktree(repoRootPath, worktreePath, force = force)
            }
            result.onSuccess {
                try {
                    localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                        repoRootPath = repoRootPath,
                        logContext = "after archive",
                    )
                    awaitWorktreeRemoval(
                        repoRootPath = repoRootPath,
                        worktreePath = normalizedWorktreePath,
                    )
                } finally {
                    state.archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
                }
            }.onFailure { failure ->
                state.archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
                logger.error(failure) { "Failed to archive worktree $worktreePath" }
                if (!force && failure.isDirtyWorktreeArchiveFailure()) {
                    state.forceArchiveWorktreeRequest.value = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
                } else {
                    errorReporter.enqueueActionError(failure.message ?: "Failed to archive worktree")
                }
                localRepositories.refreshLocalRepositoryWorktreesBestEffort(
                    repoRootPath = repoRootPath,
                    logContext = "after archive failure",
                )
            }
        }
    }

    private suspend fun awaitWorktreeRemoval(
        repoRootPath: String,
        worktreePath: String,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val removalObserved = withTimeoutOrNull(worktreeRemovalWaitTimeout) {
            state.localRepositories.first { repositories ->
                val repository = repositories.firstOrNull {
                    it.path.normalizedRepoPath() == normalizedRepoRootPath
                }
                repository == null ||
                    repository.worktrees.none { it.path.normalizedRepoPath() == worktreePath }
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
