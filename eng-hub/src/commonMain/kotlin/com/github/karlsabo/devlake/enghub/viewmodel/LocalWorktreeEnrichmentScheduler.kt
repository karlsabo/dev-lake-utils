package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.GitWorktreeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Runs worktree enrichment per repository on background jobs, one task at a time, so a blocked or slow
 * origin lookup never blocks local-row discovery, polling, or another repository's enrichment.
 *
 * Each repository's queue keeps only the newest task while a lookup is in flight, so a blocked lookup
 * cannot accumulate lookups. A task whose request was superseded is discarded by its `applyEnrichment`
 * request-token guard when it eventually runs; losing a conflation race to an older task only delays
 * enrichment until the next refresh schedules it.
 */
internal class LocalWorktreeEnrichmentScheduler(
    private val scope: CoroutineScope,
    private val gitWorktreeApi: GitWorktreeApi,
) {
    private val taskQueuesByRepoPath =
        MutableStateFlow<Map<String, SendChannel<EnrichmentTask>>>(emptyMap())

    /** Queues enrichment of [worktrees]; [applyEnrichment] runs once the lookup settles. */
    fun schedule(
        repoRootPath: String,
        normalizedRepoRootPath: String,
        worktrees: List<LocalWorktreeUiState>,
        applyEnrichment: (Result<List<LocalWorktreeUiState>>) -> Unit,
    ) {
        taskQueueFor(normalizedRepoRootPath).trySend(EnrichmentTask(repoRootPath, worktrees, applyEnrichment))
    }

    private fun taskQueueFor(normalizedRepoRootPath: String): SendChannel<EnrichmentTask> {
        while (true) {
            val taskQueues = taskQueuesByRepoPath.value
            taskQueues[normalizedRepoRootPath]?.let { return it }
            val taskQueue = Channel<EnrichmentTask>(capacity = Channel.CONFLATED)
            if (taskQueuesByRepoPath.compareAndSet(taskQueues, taskQueues + (normalizedRepoRootPath to taskQueue))) {
                scope.launch { runEnrichments(taskQueue) }
                return taskQueue
            }
        }
    }

    private suspend fun runEnrichments(taskQueue: ReceiveChannel<EnrichmentTask>) {
        for (task in taskQueue) {
            val enrichedWorktrees = runCatching {
                gitWorktreeApi.enrichLocalWorktreeUiStates(task.repoRootPath, task.worktrees)
            }.rethrowCancellation()
            task.applyEnrichment(
                enrichedWorktrees.onFailure { failure ->
                    logger.error(failure) { "Failed to enrich worktrees for ${task.repoRootPath}" }
                },
            )
        }
    }

    /** One repository's published rows to enrich, and how to apply the outcome. */
    private class EnrichmentTask(
        val repoRootPath: String,
        val worktrees: List<LocalWorktreeUiState>,
        val applyEnrichment: (Result<List<LocalWorktreeUiState>>) -> Unit,
    )
}
