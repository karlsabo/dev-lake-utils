package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class LocalWorktreeArchiveEntries(
    private val state: EngHubViewModelState,
) {
    private val leases = MutableStateFlow<Map<String, LocalWorktreeMutationGuard.Lease>>(emptyMap())

    fun expose(
        job: WorktreeArchiveJob,
        lease: LocalWorktreeMutationGuard.Lease,
        newlyQueued: Boolean = false,
    ) {
        leases.update { it + (job.worktreePath to lease) }
        if (newlyQueued) state.newlyQueuedArchiveIds.update { it + job.queueId }
        state.queuedWorktreeArchives.update { jobs ->
            jobs.filterNot { it.worktreePath == job.worktreePath } + job
        }
    }

    fun clear(worktreePath: String, queueId: String) {
        if (state.queuedWorktreeArchives.value.none { it.worktreePath == worktreePath && it.queueId == queueId }) return
        // Observers of a cleared entry must be able to acquire the worktree for another mutation.
        release(worktreePath)
        state.queuedWorktreeArchives.update { jobs ->
            jobs.filterNot { it.worktreePath.normalizedRepositoryPath() == worktreePath && it.queueId == queueId }
        }
        state.newlyQueuedArchiveIds.update { it - queueId }
        logger.info { "Cleared archive entry for worktree $worktreePath" }
    }

    private fun release(worktreePath: String) {
        while (true) {
            val current = leases.value
            val lease = current[worktreePath] ?: return
            if (leases.compareAndSet(current, current - worktreePath)) {
                lease.release()
                return
            }
        }
    }
}
