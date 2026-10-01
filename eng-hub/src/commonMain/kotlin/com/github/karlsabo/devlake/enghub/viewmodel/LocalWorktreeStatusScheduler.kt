package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One active status call and one latest pending refresh per repository/worktree path; idle slots are removed. */
internal class LocalWorktreeStatusScheduler(
    private val scope: CoroutineScope,
    private val state: EngHubViewModelState,
    private val checkStatus: suspend (String) -> Boolean,
) {
    private val slots = MutableStateFlow<Map<Pair<String, String>, Slot>>(emptyMap())
    private val tracker = LocalWorktreeStatusTracker(state)

    fun schedule(
        root: String,
        request: LocalRepositoryWorktreeRequest,
        rows: List<LocalWorktreeUiState>,
    ) {
        if (!scope.isActive) return
        rows.forEach { row -> offer(Task(root, request, row)) }
    }

    private fun offer(task: Task) {
        val key = task.key
        while (true) {
            val current = slots.value
            val slot = current[key]
            val superseded = slot != null && task.request.order < slot.newestOrder
            if (!ownsRequest(task) || superseded) return
            // Retain even an unchanged refresh: it may have reset status after the active result was published.
            val next = Slot(pending = task.takeIf { slot != null }, newestOrder = task.request.order)
            if (slots.compareAndSet(current, current + (key to next))) {
                if (slot == null) scope.launch { runTasks(task) }
                return
            }
        }
    }

    private suspend fun runTasks(first: Task) {
        var task: Task? = first
        try {
            while (task != null) {
                scope.coroutineContext.ensureActive()
                checkAndPublish(task)
                task = takeNext(first.key)
            }
        } finally {
            if (!scope.isActive) slots.update { it - first.key }
        }
    }

    private suspend fun checkAndPublish(task: Task) {
        val visible = state.localRepositories.value.any { repository ->
            repository.path.normalizedRepositoryPath() == task.root && repository.statusRequest != null &&
                repository.worktrees.any { it.checkout === task.row.checkout }
        }
        if (!visible) return
        val result = runCatching { checkStatus(task.row.path) }.rethrowCancellation()
        scope.coroutineContext.ensureActive()
        result.onSuccess { dirty ->
            tracker.publishCheckout(task.root, task.request, task.row, dirty)
        }.onFailure { failure ->
            logger.error(failure) { "Failed to check worktree status for ${task.row.path}" }
        }
    }

    private fun ownsRequest(task: Task): Boolean = state.localRepositories.value.any { repository ->
        repository.path.normalizedRepositoryPath() == task.root && repository.statusRequest === task.request &&
            repository.worktrees.any { it.checkout === task.row.checkout }
    }

    private fun takeNext(key: Pair<String, String>): Task? {
        while (true) {
            val current = slots.value
            val pending = current[key]?.pending
            val next = if (pending == null) {
                current - key
            } else {
                current + (key to Slot(null, pending.request.order))
            }
            if (slots.compareAndSet(current, next)) return pending
        }
    }

    private data class Slot(
        val pending: Task?,
        val newestOrder: Long,
    )
    private data class Task(
        val root: String,
        val request: LocalRepositoryWorktreeRequest,
        val row: LocalWorktreeUiState,
    ) {
        val key: Pair<String, String> = root to row.path.normalizedRepositoryPath()
    }
}
