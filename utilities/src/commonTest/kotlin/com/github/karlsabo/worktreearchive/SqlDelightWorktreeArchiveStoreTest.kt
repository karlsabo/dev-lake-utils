package com.github.karlsabo.worktreearchive

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightWorktreeArchiveStoreTest {
    @Test
    fun savesAndReloadsQueuedJobs() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val loginJob = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)
        val searchJob = queuedJob("/repos/widgets-feature-search", "feature/search", 2_000)

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(loginJob)
            store.saveJob(searchJob)

            assertEquals(
                listOf(loginJob, searchJob),
                SqlDelightWorktreeArchiveStore(databasePath = databasePath).listJobs(),
            )
        } finally {
            deleteRecursively(testDirectory)
        }
    }

    @Test
    fun deletingQueuedJobRemovesItsPersistedRecord() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val queued = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(queued)

            assertTrue(store.deleteQueuedJob(queued.worktreePath))
            assertEquals(emptyList(), store.listJobs())
            assertFalse(store.deleteQueuedJob(queued.worktreePath))
        } finally {
            deleteRecursively(testDirectory)
        }
    }

    @Test
    fun deletingQueuedJobDoesNotRemoveJobAfterRemovalStarts() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val removing = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000).copy(
            state = WorktreeArchiveLifecycleState.REMOVING,
            stateUpdatedAtEpochMs = 2_000,
        )

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(removing)

            assertFalse(store.deleteQueuedJob(removing.worktreePath))
            assertEquals(listOf(removing), store.listJobs())
        } finally {
            deleteRecursively(testDirectory)
        }
    }

    @Test
    fun savingSamePathReplacesItsPersistedState() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val queued = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)
        val removing = queued.copy(
            state = WorktreeArchiveLifecycleState.REMOVING,
            stateUpdatedAtEpochMs = 2_000,
        )

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(queued)
            store.saveJob(removing)

            assertEquals(listOf(removing), store.listJobs())
        } finally {
            deleteRecursively(testDirectory)
        }
    }
}

private fun queuedJob(
    path: String,
    branch: String,
    queuedAt: Long,
) = WorktreeArchiveJob(
    repositoryRootPath = "/repos/widgets",
    worktreePath = path,
    branch = branch,
    state = WorktreeArchiveLifecycleState.QUEUED,
    queuedAtEpochMs = queuedAt,
    stateUpdatedAtEpochMs = queuedAt,
    deadlineAtEpochMs = queuedAt + 60_000,
)

private fun createTestDirectory(): Path {
    val path = Path(SystemTemporaryDirectory, "worktree-archive-store-${Random.nextLong().toULong().toString(16)}")
    SystemFileSystem.createDirectories(path)
    return path
}

private fun deleteRecursively(path: Path) {
    if (!SystemFileSystem.exists(path)) return
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    SystemFileSystem.delete(path, mustExist = false)
}
