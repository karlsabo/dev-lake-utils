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
    val queueId: String,
    val state: WorktreeArchiveLifecycleState,
    val queuedAtEpochMs: Long,
    val stateUpdatedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val errorMessage: String? = null,
)

interface WorktreeArchiveStore {
    fun listJobs(): List<WorktreeArchiveJob>

    fun saveJob(job: WorktreeArchiveJob)

    /** Claims the identified queued job for removal. Returns false if it was canceled, replaced, or already claimed. */
    fun transitionQueuedJobToRemoving(
        worktreePath: String,
        queueId: String,
        stateUpdatedAtEpochMs: Long,
    ): Boolean

    /** Deletes only the identified job while it is still cancelable. */
    fun deleteQueuedJob(worktreePath: String, queueId: String): Boolean
}
