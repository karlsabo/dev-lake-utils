package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PerRepositoryConflatedTaskQueueTest {
    @Test
    fun blockedRepositoryKeepsOnlyNewestPendingTaskWithoutBlockingAnotherRepository() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val queue = PerRepositoryConflatedTaskQueue<Int>(backgroundScope) { task ->
            started += task
            if (task == 1) release.await()
        }
        queue.schedule("api", 1)
        runCurrent()
        repeat(100) { queue.schedule("api", it + 2) }
        queue.schedule("web", 200)
        runCurrent()
        assertEquals(listOf(1, 200), started)

        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 200, 101), started)
    }
}
