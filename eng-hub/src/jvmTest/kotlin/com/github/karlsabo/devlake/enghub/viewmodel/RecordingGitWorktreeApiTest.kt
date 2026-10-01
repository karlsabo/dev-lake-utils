package com.github.karlsabo.devlake.enghub.viewmodel

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingGitWorktreeApiTest {
    @Test
    fun dirtyStatusRecordingKeepsStableSnapshotsAndDuplicateCalls() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(isDirtyForWorktreePath = { it == "dirty" }),
        )
        val emptySnapshot = api.worktreeIsDirtyCalls
        assertTrue(api.worktreeIsDirty("dirty"))
        val firstSnapshot = api.worktreeIsDirtyCalls
        assertFalse(api.worktreeIsDirty("clean"))
        assertTrue(api.worktreeIsDirty("dirty"))

        assertEquals(emptyList(), emptySnapshot)
        assertEquals(listOf("dirty"), firstSnapshot)
        assertEquals(listOf("dirty", "clean", "dirty"), api.worktreeIsDirtyCalls)
    }

    @Test
    fun dirtyStatusRecordingRetainsEveryConcurrentCall() {
        val workerCount = 4
        val callsPerWorker = 100
        val start = CyclicBarrier(workerCount)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(isDirtyForWorktreePath = { false }),
        )
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val workers = (0 until workerCount).map { worker ->
                executor.submit {
                    repeat(callsPerWorker) { call ->
                        start.await()
                        assertFalse(api.worktreeIsDirty("$worker/$call"))
                    }
                }
            }
            workers.forEach { it.get() }
            val expected = (0 until workerCount).flatMap { worker ->
                (0 until callsPerWorker).map { call -> "$worker/$call" }
            }
            assertEquals(expected.sorted(), api.worktreeIsDirtyCalls.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun dirtyStatusRecordingPrecedesResponseFailure() {
        val api = RecordingGitWorktreeApi()

        assertFailsWith<IllegalStateException> { api.worktreeIsDirty("unconfigured") }

        assertEquals(listOf("unconfigured"), api.worktreeIsDirtyCalls)
    }
}
