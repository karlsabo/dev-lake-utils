package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LocalWorktreeArchiveRestoration(
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val archive: WorktreeArchiveDependencies,
    private val errorReporter: ActionErrorReporter,
    private val expose: (WorktreeArchiveJob, LocalWorktreeMutationGuard.Lease) -> Unit,
    private val resumeRemoval: (WorktreeArchiveJob) -> Unit,
) {
    suspend fun restore() {
        runCatching {
            val jobs = archive.store.listJobs()
            currentCoroutineContext().ensureActive()
            jobs.filter {
                it.state == WorktreeArchiveLifecycleState.QUEUED ||
                    it.state == WorktreeArchiveLifecycleState.REMOVING
            }.forEach { job ->
                runCatching {
                    if (job.state == WorktreeArchiveLifecycleState.QUEUED) restoreJob(job) else resumeJob(job)
                }.rethrowCancellation().onFailure { report(it) }
            }
        }.rethrowCancellation().onFailure { report(it) }
    }

    private suspend fun restoreJob(job: WorktreeArchiveJob) {
        val root = job.repositoryRootPath.normalizedRepositoryPath()
        val path = job.worktreePath.normalizedRepositoryPath()
        check(root.isNotEmpty() && path.isNotEmpty() && root != path && path == job.worktreePath) {
            "Invalid queued worktree identity: ${job.worktreePath}"
        }
        check(state.localRepositories.value.any { it.path.normalizedRepositoryPath() == root }) {
            "Repository is no longer configured: ${job.repositoryRootPath}"
        }
        val discovered = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
        currentCoroutineContext().ensureActive()
        val worktree = discovered.firstOrNull { it.path.normalizedRepositoryPath() == path }
        val error = when {
            worktree == null -> "Queued worktree is no longer registered: $path"
            worktree.branch != job.branch -> "Queued worktree branch has changed: $path"
            !archive.checkoutPresent(path) -> "Queued worktree checkout is missing: $path"
            else -> null
        }
        val lease = checkNotNull(state.localWorktreeMutationGuard.tryAcquire(path)) {
            "Worktree mutation already in progress: $path"
        }
        var exposed = false
        try {
            val readyAt = archive.now().toEpochMilliseconds()
            val restored = job.copy(
                state = if (error == null) {
                    WorktreeArchiveLifecycleState.QUEUED
                } else {
                    WorktreeArchiveLifecycleState.FAILED
                },
                stateUpdatedAtEpochMs = maxOf(readyAt, job.stateUpdatedAtEpochMs + 1),
                deadlineAtEpochMs = readyAt + archive.delay.inWholeMilliseconds,
                errorMessage = error,
            )
            if (archive.store.startup.restoreQueuedJob(
                    job,
                    restored.stateUpdatedAtEpochMs,
                    restored.deadlineAtEpochMs,
                    error,
                )
            ) {
                currentCoroutineContext().ensureActive()
                expose(restored, lease)
                exposed = true
                if (error != null) errorReporter.enqueueActionError(error)
            }
        } finally {
            if (!exposed) lease.release()
        }
    }

    private suspend fun resumeJob(job: WorktreeArchiveJob) {
        val root = job.repositoryRootPath.normalizedRepositoryPath()
        val path = job.worktreePath.normalizedRepositoryPath()
        check(root.isNotEmpty() && path.isNotEmpty() && root != path && path == job.worktreePath) {
            "Invalid removing worktree identity: ${job.worktreePath}"
        }
        check(state.localRepositories.value.any { it.path.normalizedRepositoryPath() == root }) {
            "Repository is no longer configured: ${job.repositoryRootPath}"
        }
        val lease = checkNotNull(state.localWorktreeMutationGuard.tryAcquire(path)) {
            "Worktree mutation already in progress: $path"
        }
        var exposed = false
        try {
            val registered = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
                .firstOrNull { it.path.normalizedRepositoryPath() == path }
            val identityError = resumeIdentityError(job, path, registered?.branch)
            currentCoroutineContext().ensureActive()
            if (identityError != null) {
                val failedAt = maxOf(archive.now().toEpochMilliseconds(), job.stateUpdatedAtEpochMs + 1)
                val failed = job.copy(
                    state = WorktreeArchiveLifecycleState.FAILED,
                    stateUpdatedAtEpochMs = failedAt,
                    errorMessage = identityError,
                )
                if (archive.store.transitionRemovingJobToFailed(job, identityError, failedAt)) {
                    currentCoroutineContext().ensureActive()
                    expose(failed, lease)
                    exposed = true
                    errorReporter.enqueueActionError(identityError)
                }
            } else {
                check(archive.store.listJobs().any { it == job }) {
                    "Removing worktree archive changed during startup: $path"
                }
                currentCoroutineContext().ensureActive()
                expose(job, lease)
                exposed = true
                resumeRemoval(job)
            }
        } finally {
            if (!exposed) lease.release()
        }
    }

    private fun resumeIdentityError(
        job: WorktreeArchiveJob,
        path: String,
        registeredBranch: String?,
    ): String? = when {
        registeredBranch != null && registeredBranch != job.branch ->
            "Cannot resume archive: worktree branch no longer matches: $path"

        // Without registration, leftover files cannot be distinguished from a replacement checkout.
        registeredBranch == null && archive.pathPresent(path) ->
            "Cannot resume archive: checkout identity cannot be verified: $path"

        registeredBranch != null && archive.pathPresent(path) && !archive.checkoutPresent(path) ->
            "Cannot resume archive: checkout identity cannot be verified: $path"

        else -> null
    }

    private suspend fun report(failure: Throwable) {
        currentCoroutineContext().ensureActive()
        logger.error(failure) { "Failed to restore queued worktree archive" }
        errorReporter.enqueueActionError("Failed to restore queued worktree archive: ${failure.message ?: failure}")
    }
}
