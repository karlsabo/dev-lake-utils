package com.github.karlsabo.notifications

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightNotificationSubscriptionStoreTest {

    @Test
    fun savesAndReloadsUnsubscribedThreadIds() {
        val testDir = createTempDir()
        val databasePath = Path(testDir, "eng-hub-notifications.db").toString()

        try {
            val store = SqlDelightNotificationSubscriptionStore(databasePath = databasePath)
            store.saveUnsubscribedThread(
                threadId = "thread-1",
                repositoryFullName = "example-org/example-repo",
                subjectType = "PullRequest",
                unsubscribedAtEpochMs = 1_000,
            )
            store.saveUnsubscribedThread(
                threadId = "thread-1",
                repositoryFullName = "example-org/example-repo",
                subjectType = "PullRequest",
                unsubscribedAtEpochMs = 2_000,
            )
            store.saveUnsubscribedThread(
                threadId = "thread-2",
                repositoryFullName = "example-org/example-repo",
                subjectType = "Issue",
                unsubscribedAtEpochMs = 3_000,
            )

            val reloadedStore = SqlDelightNotificationSubscriptionStore(databasePath = databasePath)

            assertEquals(
                setOf("thread-1", "thread-2"),
                reloadedStore.listUnsubscribedThreadIds(),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }
}

private fun createTempDir(): Path {
    val path = Path(
        SystemTemporaryDirectory,
        "notification-store-test-${Random.nextLong().toULong().toString(16)}",
    )
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
