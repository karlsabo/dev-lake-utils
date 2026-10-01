package com.github.karlsabo.worktreearchive

internal class SqlDelightWorktreeArchiveStartupStore(
    private val queries: WorktreeArchiveJobsQueries,
) : WorktreeArchiveStartupStore {
    override fun restoreQueuedJob(
        job: WorktreeArchiveJob,
        stateUpdatedAtEpochMs: Long,
        deadlineAtEpochMs: Long,
        errorMessage: String?,
    ): Boolean = queries.transactionWithResult {
        val restoredState = if (errorMessage == null) {
            WorktreeArchiveLifecycleState.QUEUED
        } else {
            WorktreeArchiveLifecycleState.FAILED
        }
        queries.restoreQueuedJob(
            restoredState.name,
            stateUpdatedAtEpochMs,
            deadlineAtEpochMs,
            errorMessage,
            job.worktreePath,
            job.queueId,
            job.stateUpdatedAtEpochMs,
        )
        queries.changedRowCount().executeAsOne() > 0
    }
}
