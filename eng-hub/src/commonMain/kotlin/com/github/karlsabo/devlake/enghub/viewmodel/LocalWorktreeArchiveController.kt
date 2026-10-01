package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

internal val DEFAULT_WORKTREE_ARCHIVE_DELAY: Duration = 60.seconds
private val ARCHIVE_CLAIM_RETRY_DELAY: Duration = 5.seconds
private const val QUEUE_ID_RADIX = 16

internal class LocalWorktreeArchiveController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val archive: WorktreeArchiveDependencies,
    private val errorReporter: ActionErrorReporter,
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
        val queueId = state.queuedWorktreeArchives.value
            .firstOrNull { it.worktreePath.normalizedRepositoryPath() == normalizedWorktreePath }
            ?.queueId ?: return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { archive.store.deleteQueuedJob(normalizedWorktreePath, queueId) }
                .rethrowCancellation()
                .onSuccess { deleted ->
                    if (deleted) completeQueuedArchiveUndo(normalizedWorktreePath, queueId)
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
        val queuedAt = archive.now().toEpochMilliseconds()
        val archiveJob = WorktreeArchiveJob(
            repositoryRootPath = repository.path,
            worktreePath = worktreePath,
            branch = worktree.branch,
            queueId = "${Random.nextLong().toULong().toString(QUEUE_ID_RADIX)}-" +
                Random.nextLong().toULong().toString(QUEUE_ID_RADIX),
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = queuedAt,
            stateUpdatedAtEpochMs = queuedAt,
            deadlineAtEpochMs = queuedAt + archive.delay.inWholeMilliseconds,
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
                    archive.store.saveJob(archiveJob)
                    queuedArchiveLeases.update { leases ->
                        leases + (archiveJob.worktreePath to mutationLease)
                    }
                    state.queuedWorktreeArchives.update { jobs ->
                        jobs.filterNot { it.worktreePath == archiveJob.worktreePath } + archiveJob
                    }
                    safelyQueued = true
                    scheduleArchiveRemoval(archiveJob)
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

    private fun scheduleArchiveRemoval(archiveJob: WorktreeArchiveJob) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val remainingDelayMs =
                (archiveJob.deadlineAtEpochMs - archive.now().toEpochMilliseconds()).coerceAtLeast(0)
            archive.waitForDeadline(remainingDelayMs.toDuration(DurationUnit.MILLISECONDS))
            var reportedFailure = false
            while (true) {
                if (startArchiveRemoval(archiveJob, reportFailure = !reportedFailure)) return@launch
                reportedFailure = true
                archive.waitForDeadline(ARCHIVE_CLAIM_RETRY_DELAY)
            }
        }
    }

    private fun startArchiveRemoval(queuedJob: WorktreeArchiveJob, reportFailure: Boolean): Boolean {
        val removingAt = archive.now().toEpochMilliseconds()
        val claimed = runCatching {
            archive.store.transitionQueuedJobToRemoving(
                worktreePath = queuedJob.worktreePath,
                queueId = queuedJob.queueId,
                stateUpdatedAtEpochMs = removingAt,
            )
        }.rethrowCancellation().getOrElse { failure ->
            logger.error(failure) { "Failed to start queued archive for worktree ${queuedJob.worktreePath}" }
            if (reportFailure) {
                errorReporter.enqueueActionError(
                    failure.message?.let { "Failed to start worktree archive: $it" }
                        ?: "Failed to start worktree archive",
                )
            }
            return false
        }
        if (claimed) {
            val removingJob = queuedJob.copy(
                state = WorktreeArchiveLifecycleState.REMOVING,
                stateUpdatedAtEpochMs = removingAt,
            )
            state.queuedWorktreeArchives.update { jobs ->
                jobs.map { job -> if (job.worktreePath == queuedJob.worktreePath) removingJob else job }
            }
            logger.info { "Removing archived worktree ${queuedJob.worktreePath}" }
            runCatching {
                gitWorktreeApi.archiveWorktree(
                    repoPath = queuedJob.repositoryRootPath,
                    worktreePath = queuedJob.worktreePath,
                    force = false,
                )
            }.rethrowCancellation().onFailure { failure ->
                logger.error(failure) { "Worktree removal failed for ${queuedJob.worktreePath}" }
            }
        }
        return true
    }

    private fun completeQueuedArchiveUndo(worktreePath: String, queueId: String) {
        // Observers of completed Undo must be able to acquire the worktree for another mutation.
        releaseQueuedArchiveLease(worktreePath)
        state.queuedWorktreeArchives.update { jobs ->
            jobs.filterNot { it.worktreePath.normalizedRepositoryPath() == worktreePath && it.queueId == queueId }
        }
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
