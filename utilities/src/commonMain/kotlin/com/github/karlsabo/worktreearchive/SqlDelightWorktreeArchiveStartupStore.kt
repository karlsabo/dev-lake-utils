package com.github.karlsabo.worktreearchive

internal class SqlDelightWorktreeArchiveStartupStore(
    private val queries: WorktreeArchiveJobsQueries,
) : WorktreeArchiveStartupStore {
    override fun insertQueuedJob(job: WorktreeArchiveJob): Boolean = queries.transactionWithResult {
        queries.insertQueuedJob(
            worktree_path = job.worktreePath,
            repository_root_path = job.repositoryRootPath,
            branch = job.branch,
            queue_id = job.queueId,
            lifecycle_state = job.state.name,
            queued_at_epoch_ms = job.queuedAtEpochMs,
            state_updated_at_epoch_ms = job.stateUpdatedAtEpochMs,
            deadline_at_epoch_ms = job.deadlineAtEpochMs,
            error_message = job.errorMessage,
        )
        queries.changedRowCount().executeAsOne() > 0
    }

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
