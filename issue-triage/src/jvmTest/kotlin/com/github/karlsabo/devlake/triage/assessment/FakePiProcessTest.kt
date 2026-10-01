package com.github.karlsabo.devlake.triage.assessment

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakePiProcessTest {
    @Test
    fun `pid readiness is invisible until the complete pid is atomically published`() {
        val directory = Files.createTempDirectory("fake-pi-publication")
        val pidFile = directory.resolve("pid.txt")
        val stagedReady = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                publishFakePiPid(pidFile, ProcessHandle.current().pid()) { staged ->
                    assertEquals(ProcessHandle.current().pid(), Files.readString(staged).toLong())
                    stagedReady.countDown()
                    assertTrue(publish.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(stagedReady.await(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(pidFile))
            publish.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(ProcessHandle.current().pid(), Files.readString(pidFile).toLong())
            Files.list(directory).use { files -> assertEquals(listOf(pidFile), files.toList()) }
        } finally {
            publish.countDown()
            executor.shutdownNow()
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(directory)
        }
    }
}
