package com.github.karlsabo.worktreearchive

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import kotlinx.io.files.Path

val engHubWorktreeArchiveDatabasePath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "eng-hub-worktree-archive.db")

class SqlDelightWorktreeArchiveStore(
    driverFactory: WorktreeArchiveDatabaseDriverFactory = WorktreeArchiveDatabaseDriverFactory(),
    databasePath: String = engHubWorktreeArchiveDatabasePath.toString(),
) : WorktreeArchiveStore {
    private val queries = WorktreeArchiveDatabase(
        driverFactory.createDriver(databasePath),
    ).worktreeArchiveJobsQueries

    override fun listJobs(): List<WorktreeArchiveJob> = queries.selectAll {
            worktreePath,
            repositoryRootPath,
            branch,
            lifecycleState,
            queuedAtEpochMs,
            stateUpdatedAtEpochMs,
            deadlineAtEpochMs,
            errorMessage,
        ->
        WorktreeArchiveJob(
            repositoryRootPath = repositoryRootPath,
            worktreePath = worktreePath,
            branch = branch,
            state = WorktreeArchiveLifecycleState.valueOf(lifecycleState),
            queuedAtEpochMs = queuedAtEpochMs,
            stateUpdatedAtEpochMs = stateUpdatedAtEpochMs,
            deadlineAtEpochMs = deadlineAtEpochMs,
            errorMessage = errorMessage,
        )
    }.executeAsList()

    override fun saveJob(job: WorktreeArchiveJob) {
        queries.upsertJob(
            worktree_path = job.worktreePath,
            repository_root_path = job.repositoryRootPath,
            branch = job.branch,
            lifecycle_state = job.state.name,
            queued_at_epoch_ms = job.queuedAtEpochMs,
            state_updated_at_epoch_ms = job.stateUpdatedAtEpochMs,
            deadline_at_epoch_ms = job.deadlineAtEpochMs,
            error_message = job.errorMessage,
        )
    }

    override fun deleteQueuedJob(worktreePath: String): Boolean = queries.transactionWithResult {
        queries.deleteQueuedJob(worktreePath)
        queries.changedRowCount().executeAsOne() > 0
    }
}
