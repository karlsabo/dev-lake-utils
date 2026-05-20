package com.github.karlsabo.notifications

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightNotificationIgnoreStoreTest {

    @Test
    fun savesAndReloadsIgnoredThreadIds() {
        val testDir = createNotificationStoreTestDir()
        val databasePath = Path(testDir, "eng-hub-notifications.db").toString()

        try {
            val store = SqlDelightNotificationIgnoreStore(databasePath = databasePath)
            store.saveIgnoredThread(
                threadId = "thread-1",
                repositoryFullName = "example-org/example-repo",
                subjectType = "PullRequest",
                reason = NotificationIgnoreReason.UNSUBSCRIBED,
                ignoredAtEpochMs = 1_000,
            )
            store.saveIgnoredThread(
                threadId = "thread-1",
                repositoryFullName = "example-org/example-repo",
                subjectType = "PullRequest",
                reason = NotificationIgnoreReason.UNSUBSCRIBED,
                ignoredAtEpochMs = 2_000,
            )
            store.saveIgnoredThread(
                threadId = "thread-2",
                repositoryFullName = "example-org/example-repo",
                subjectType = "Issue",
                reason = NotificationIgnoreReason.DONE,
                ignoredAtEpochMs = 3_000,
            )

            val reloadedStore = SqlDelightNotificationIgnoreStore(databasePath = databasePath)

            assertEquals(
                setOf("thread-1", "thread-2"),
                reloadedStore.listIgnoredThreadIds(),
            )
            assertEquals(
                NotificationIgnoreReason.DONE.name,
                NotificationDatabase(NotificationDatabaseDriverFactory().createDriver(databasePath))
                    .ignoredNotificationThreadsQueries
                    .selectIgnoreReason("thread-2")
                    .executeAsOne(),
            )
        } finally {
            deleteRecursively(testDir)
        }
    }
}
