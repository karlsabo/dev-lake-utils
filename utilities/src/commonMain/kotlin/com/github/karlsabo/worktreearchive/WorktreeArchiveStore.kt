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

    /** Records failure only for the matching removal attempt, without overwriting a newer state. */
    fun transitionRemovingJobToFailed(
        job: WorktreeArchiveJob,
        errorMessage: String,
        stateUpdatedAtEpochMs: Long,
    ): Boolean

    /** Retains dirty refusal only for the matching removal attempt. */
    fun transitionRemovingJobToNeedsForceConfirmation(
        job: WorktreeArchiveJob,
        errorMessage: String,
        stateUpdatedAtEpochMs: Long,
    ): Boolean

    /** Claims only the explicitly confirmed attempt; repeated or stale requests return false. */
    fun transitionNeedsForceConfirmationJobToRemoving(job: WorktreeArchiveJob, stateUpdatedAtEpochMs: Long): Boolean

    /** Claims the matching failed attempt for retry; repeated or stale requests return false. */
    fun transitionFailedJobToRemoving(job: WorktreeArchiveJob, stateUpdatedAtEpochMs: Long): Boolean

    /** Forgets only the matching failed attempt. This is not cancellation or restoration. */
    fun deleteFailedJob(job: WorktreeArchiveJob): Boolean

    /** Deletes only the identified removing job after successful cleanup and reconciliation. */
    fun deleteRemovingJob(worktreePath: String, queueId: String): Boolean

    /** Deletes only the identified job while it is still cancelable. */
    fun deleteQueuedJob(worktreePath: String, queueId: String): Boolean
}
