package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Runs tasks for each repository key one at a time on [scope], keeping only the newest pending task
 * per key while one is in flight, so a blocked task neither delays other keys nor accumulates work.
 *
 * Task bodies must handle their own failures: an exception escaping [runTask] cancels that key's
 * worker, and later tasks for the key go unprocessed.
 */
internal class PerRepositoryConflatedTaskQueue<T>(
    private val scope: CoroutineScope,
    private val runTask: suspend (T) -> Unit,
) {
    private val taskQueuesByKey = MutableStateFlow<Map<String, SendChannel<T>>>(emptyMap())

    /** Queues [task] for [key], replacing any pending task for the key that has not started. */
    fun schedule(key: String, task: T) {
        taskQueueFor(key).trySend(task)
    }

    private fun taskQueueFor(key: String): SendChannel<T> {
        while (true) {
            val taskQueues = taskQueuesByKey.value
            taskQueues[key]?.let { return it }
            val taskQueue = Channel<T>(capacity = Channel.CONFLATED)
            if (taskQueuesByKey.compareAndSet(taskQueues, taskQueues + (key to taskQueue))) {
                scope.launch {
                    for (task in taskQueue) {
                        currentCoroutineContext().ensureActive()
                        runTask(task)
                    }
                }
                return taskQueue
            }
        }
    }
}
