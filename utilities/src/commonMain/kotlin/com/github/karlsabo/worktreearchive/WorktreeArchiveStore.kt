package com.github.karlsabo.worktreearchive

enum class WorktreeArchiveLifecycleState {
    QUEUED,
    REMOVING,
    FAILED,
    NEEDS_FORCE_CONFIRMATION,
}

data class WorktreeArchiveJob(
    val repositoryRootPath: String,
    val worktreePath: String,
    val branch: String,
    val state: WorktreeArchiveLifecycleState,
    val queuedAtEpochMs: Long,
    val stateUpdatedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val errorMessage: String? = null,
)

interface WorktreeArchiveStore {
    fun listJobs(): List<WorktreeArchiveJob>

    fun saveJob(job: WorktreeArchiveJob)

    /** Deletes the job only while it is still cancelable. */
    fun deleteQueuedJob(worktreePath: String): Boolean
}
