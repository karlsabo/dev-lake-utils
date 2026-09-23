package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import com.github.karlsabo.worktreearchive.WorktreeArchiveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal val DEFAULT_WORKTREE_ARCHIVE_DELAY: Duration = 60.seconds

internal class LocalWorktreeArchiveController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val archiveStore: WorktreeArchiveStore,
    private val errorReporter: ActionErrorReporter,
    private val archiveDelay: Duration = DEFAULT_WORKTREE_ARCHIVE_DELAY,
    private val now: () -> Instant = Clock.System::now,
) {
    fun archiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepositoryPath()
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        when {
            normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty() -> Unit

            normalizedRepoRootPath == normalizedWorktreePath -> {
                errorReporter.enqueueActionError("Cannot archive root worktree: $worktreePath")
            }

            else -> queueKnownWorktree(normalizedRepoRootPath, normalizedWorktreePath)
        }
    }

    fun confirmForceArchiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        val request = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
        if (state.forceArchiveWorktreeRequest.compareAndSet(expect = request, update = null)) {
            archiveLocalWorktree(repoRootPath, worktreePath)
        }
    }

    fun dismissForceArchiveWorktreeRequest() {
        state.forceArchiveWorktreeRequest.value = null
    }

    private fun queueKnownWorktree(repositoryRootPath: String, worktreePath: String) {
        val repository = state.localRepositories.value.firstOrNull {
            it.path.normalizedRepositoryPath() == repositoryRootPath
        }
        val worktree = repository?.worktrees?.firstOrNull {
            it.path.normalizedRepositoryPath() == worktreePath
        }
        if (worktree == null || worktree.isRoot) {
            errorReporter.enqueueActionError("Cannot queue unknown worktree for archive: $worktreePath")
            return
        }

        val mutationLease = state.localWorktreeMutationGuard.tryAcquire(worktreePath) ?: return
        val queuedAt = now().toEpochMilliseconds()
        val archiveJob = WorktreeArchiveJob(
            repositoryRootPath = repository.path,
            worktreePath = worktreePath,
            branch = worktree.branch,
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = queuedAt,
            stateUpdatedAtEpochMs = queuedAt,
            deadlineAtEpochMs = queuedAt + archiveDelay.inWholeMilliseconds,
        )
        persistThenExpose(archiveJob, mutationLease)
    }

    private fun persistThenExpose(
        archiveJob: WorktreeArchiveJob,
        mutationLease: LocalWorktreeMutationGuard.Lease,
    ) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            var safelyQueued = false
            try {
                runCatching {
                    archiveStore.saveJob(archiveJob)
                    state.queuedWorktreeArchives.update { jobs ->
                        jobs.filterNot { it.worktreePath == archiveJob.worktreePath } + archiveJob
                    }
                    safelyQueued = true
                    logger.info { "Queued worktree ${archiveJob.worktreePath} for archive" }
                }
                    .rethrowCancellation()
                    .onFailure { failure ->
                        logger.error(failure) { "Failed to persist queued worktree ${archiveJob.worktreePath}" }
                        errorReporter.enqueueActionError(
                            failure.message?.let { "Failed to queue worktree archive: $it" }
                                ?: "Failed to queue worktree archive",
                        )
                    }
            } finally {
                if (!safelyQueued) mutationLease.release()
            }
        }
    }
}
