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
    fun queuedJobIsAtomicallyClaimedForRemovalOnce() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val queued = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(queued)

            assertTrue(store.transitionQueuedJobToRemoving(queued.worktreePath, queued.queueId, 61_000))
            assertFalse(store.transitionQueuedJobToRemoving(queued.worktreePath, queued.queueId, 62_000))
            assertEquals(
                listOf(
                    queued.copy(
                        state = WorktreeArchiveLifecycleState.REMOVING,
                        stateUpdatedAtEpochMs = 61_000,
                    ),
                ),
                store.listJobs(),
            )
        } finally {
            deleteRecursively(testDirectory)
        }
    }

    @Test
    fun staleClaimDoesNotTransitionReplacementJob() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val original = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)
        val replacement = original.copy(queueId = "replacement")

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(original)
            store.saveJob(replacement)

            assertFalse(store.transitionQueuedJobToRemoving(original.worktreePath, original.queueId, 61_000))
            assertEquals(listOf(replacement), store.listJobs())
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

            assertTrue(store.deleteQueuedJob(queued.worktreePath, queued.queueId))
            assertEquals(emptyList(), store.listJobs())
            assertFalse(store.deleteQueuedJob(queued.worktreePath, queued.queueId))
        } finally {
            deleteRecursively(testDirectory)
        }
    }

    @Test
    fun staleUndoDoesNotDeleteReplacementJob() {
        val testDirectory = createTestDirectory()
        val databasePath = Path(testDirectory, "archive.db").toString()
        val original = queuedJob("/repos/widgets-feature-login", "feature/login", 1_000)
        val replacement = original.copy(queueId = "replacement")

        try {
            val store = SqlDelightWorktreeArchiveStore(databasePath = databasePath)
            store.saveJob(original)
            store.saveJob(replacement)

            assertFalse(store.deleteQueuedJob(original.worktreePath, original.queueId))
            assertEquals(listOf(replacement), store.listJobs())
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

            assertFalse(store.deleteQueuedJob(removing.worktreePath, removing.queueId))
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
    queueId = "queue-$queuedAt",
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
