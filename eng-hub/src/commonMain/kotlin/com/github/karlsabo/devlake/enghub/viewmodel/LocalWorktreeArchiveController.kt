package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import com.github.karlsabo.worktreearchive.WorktreeArchiveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val queuedArchiveLeases = MutableStateFlow<Map<String, LocalWorktreeMutationGuard.Lease>>(emptyMap())

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

    fun undoQueuedWorktreeArchive(worktreePath: String) {
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        if (normalizedWorktreePath.isEmpty()) return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { archiveStore.deleteQueuedJob(normalizedWorktreePath) }
                .rethrowCancellation()
                .onSuccess { deleted ->
                    if (deleted) completeQueuedArchiveUndo(normalizedWorktreePath)
                }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to cancel queued archive for worktree $normalizedWorktreePath" }
                    errorReporter.enqueueActionError(
                        failure.message?.let { "Failed to undo worktree archive: $it" }
                            ?: "Failed to undo worktree archive",
                    )
                }
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
                    queuedArchiveLeases.update { leases ->
                        leases + (archiveJob.worktreePath to mutationLease)
                    }
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

    private fun completeQueuedArchiveUndo(worktreePath: String) {
        state.queuedWorktreeArchives.update { jobs ->
            jobs.filterNot { it.worktreePath.normalizedRepositoryPath() == worktreePath }
        }
        releaseQueuedArchiveLease(worktreePath)
        logger.info { "Canceled queued archive for worktree $worktreePath" }
    }

    private fun releaseQueuedArchiveLease(worktreePath: String) {
        while (true) {
            val leases = queuedArchiveLeases.value
            val lease = leases[worktreePath] ?: return
            if (queuedArchiveLeases.compareAndSet(leases, leases - worktreePath)) {
                lease.release()
                return
            }
        }
    }
}
