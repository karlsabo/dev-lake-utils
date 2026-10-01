package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.GitWorktreeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/** Serializes enrichment per repository, retaining only the newest request queued during a lookup. */
internal class LocalWorktreeEnrichmentScheduler(
    private val scope: CoroutineScope,
    private val gitWorktreeApi: GitWorktreeApi,
) {
    private val taskQueuesByRepoPath = MutableStateFlow<Map<String, TaskQueue>>(emptyMap())

    fun schedule(
        repoRootPath: String,
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        worktrees: List<LocalWorktreeUiState>,
        applyEnrichment: (Result<List<LocalWorktreeUiState>>) -> Unit,
    ) {
        taskQueueFor(normalizedRepoRootPath).offer(
            EnrichmentTask(repoRootPath, request.order, worktrees, applyEnrichment),
        )
    }

    private fun taskQueueFor(normalizedRepoRootPath: String): TaskQueue {
        while (true) {
            val taskQueues = taskQueuesByRepoPath.value
            taskQueues[normalizedRepoRootPath]?.let { return it }
            val taskQueue = TaskQueue()
            if (taskQueuesByRepoPath.compareAndSet(taskQueues, taskQueues + (normalizedRepoRootPath to taskQueue))) {
                scope.launch { runEnrichments(taskQueue) }
                return taskQueue
            }
        }
    }

    private suspend fun runEnrichments(taskQueue: TaskQueue) {
        while (taskQueue.signals.receiveCatching().isSuccess) {
            val task = taskQueue.take() ?: continue
            scope.coroutineContext.ensureActive()
            val enrichedWorktrees = runCatching {
                gitWorktreeApi.enrichLocalWorktreeUiStates(task.repoRootPath, task.worktrees)
            }.rethrowCancellation()
            scope.coroutineContext.ensureActive()
            task.applyEnrichment(
                enrichedWorktrees.onFailure { failure ->
                    logger.error(failure) { "Failed to enrich worktrees for ${task.repoRootPath}" }
                },
            )
        }
    }

    private class TaskQueue {
        val signals = Channel<Unit>(Channel.CONFLATED)
        private val pending = MutableStateFlow(QueuedTask())

        fun offer(task: EnrichmentTask) {
            while (true) {
                val current = pending.value
                if (task.order <= current.newestOrder) return
                if (pending.compareAndSet(current, QueuedTask(task.order, task))) {
                    signals.trySend(Unit)
                    return
                }
            }
        }

        // Keep the high-water mark even after consumption: late producers must never roll it back.
        fun take(): EnrichmentTask? = pending.getAndUpdate { it.copy(task = null) }.task
    }

    private data class QueuedTask(
        val newestOrder: Long = -1,
        val task: EnrichmentTask? = null,
    )

    private class EnrichmentTask(
        val repoRootPath: String,
        val order: Long,
        val worktrees: List<LocalWorktreeUiState>,
        val applyEnrichment: (Result<List<LocalWorktreeUiState>>) -> Unit,
    )
}
