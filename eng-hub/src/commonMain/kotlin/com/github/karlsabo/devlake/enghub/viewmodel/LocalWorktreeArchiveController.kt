package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    gitWorktreeApi: GitWorktreeApi,
    private val archive: WorktreeArchiveDependencies,
    localRepositories: LocalRepositoryController,
    private val errorReporter: ActionErrorReporter,
) {
    private val entries = LocalWorktreeArchiveEntries(state)
    private val completion = LocalWorktreeArchiveCompletion(viewModel, state, localRepositories, archive.store) { job ->
        entries.clear(job.worktreePath, job.queueId)
    }
    private val removal = LocalWorktreeArchiveRemoval(
        viewModel,
        state,
        gitWorktreeApi,
        archive,
        completion,
        errorReporter,
    )

    private val restoration = LocalWorktreeArchiveRestoration(
        state,
        gitWorktreeApi,
        archive,
        errorReporter,
        ::exposeArchive,
        { job ->
            viewModel.viewModelScope.launch(Dispatchers.IO) { removal.remove(job, resumed = true) }
        },
    )
    private val startupRestoration = viewModel.viewModelScope.launch(Dispatchers.IO) { restoration.restore() }

    fun archiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepositoryPath()
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        when {
            normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty() -> Unit

            normalizedRepoRootPath == normalizedWorktreePath -> {
                errorReporter.enqueueActionError("Cannot archive root worktree: $worktreePath")
            }

            else -> {
                if (startupRestoration.isCompleted) {
                    queueKnownWorktree(normalizedRepoRootPath, normalizedWorktreePath)
                } else {
                    // A new queue request must not overwrite a startup snapshot still being restored.
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        startupRestoration.join()
                        queueKnownWorktree(normalizedRepoRootPath, normalizedWorktreePath)
                    }
                }
            }
        }
    }

    fun undoQueuedWorktreeArchive(worktreePath: String) {
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        val queueId = state.queuedWorktreeArchives.value
            .firstOrNull {
                it.worktreePath.normalizedRepositoryPath() == normalizedWorktreePath &&
                    it.state == WorktreeArchiveLifecycleState.QUEUED
            }?.queueId ?: return

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { archive.store.deleteQueuedJob(normalizedWorktreePath, queueId) }
                .rethrowCancellation()
                .onSuccess { deleted ->
                    if (deleted) entries.clear(normalizedWorktreePath, queueId)
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

    val retryFailedWorktreeArchive: (String) -> Unit = removal::retry
    val dismissFailedWorktreeArchive: (String) -> Unit = removal::dismiss

    fun requestForceArchiveLocalWorktree(worktreePath: String) {
        val job = state.queuedWorktreeArchives.value.firstOrNull {
            it.worktreePath == worktreePath.normalizedRepositoryPath() &&
                it.state == WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION
        } ?: return
        state.forceArchiveWorktreeRequest.value = ForceArchiveWorktreeUiState(
            job.repositoryRootPath,
            job.worktreePath,
            job.queueId,
            job.stateUpdatedAtEpochMs,
        )
    }

    fun dismissForceArchiveWorktreeRequest(request: ForceArchiveWorktreeUiState) {
        state.forceArchiveWorktreeRequest.compareAndSet(request, null)
    }

    val confirmForceArchiveLocalWorktree = removal::confirmForceRemoval

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
                    check(archive.store.startup.insertQueuedJob(archiveJob)) {
                        "Worktree archive is already queued: ${archiveJob.worktreePath}"
                    }
                    currentCoroutineContext().ensureActive()
                    exposeArchive(archiveJob, mutationLease)
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

    private fun exposeArchive(job: WorktreeArchiveJob, lease: LocalWorktreeMutationGuard.Lease) {
        entries.expose(job, lease)
        if (job.state == WorktreeArchiveLifecycleState.QUEUED) scheduleArchiveRemoval(job)
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

    private suspend fun startArchiveRemoval(queuedJob: WorktreeArchiveJob, reportFailure: Boolean): Boolean {
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
                jobs.map { job ->
                    if (job.worktreePath == queuedJob.worktreePath && job.queueId == queuedJob.queueId) {
                        removingJob
                    } else {
                        job
                    }
                }
            }
            logger.info { "Removing archived worktree ${queuedJob.worktreePath}" }
            removal.remove(removingJob)
        }
        return true
    }
}
