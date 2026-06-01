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
            store.saveIgnoredThreadFixtures()

            val reloadedStore = SqlDelightNotificationIgnoreStore(databasePath = databasePath)

            assertEquals(
                setOf("thread-1", "thread-2"),
                reloadedStore.listIgnoredThreadIds(),
            )
            assertEquals(
                expectedIgnoredThreads(),
                reloadedStore.listIgnoredThreads().sortedBy { it.threadId },
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

private fun SqlDelightNotificationIgnoreStore.saveIgnoredThreadFixtures() {
    saveIgnoredThread(
        threadId = "thread-1",
        repositoryFullName = "example-org/example-repo",
        subjectType = "PullRequest",
        reason = NotificationIgnoreReason.UNSUBSCRIBED,
        ignoredAtEpochMs = 1_000,
    )
    saveIgnoredThread(
        threadId = "thread-1",
        repositoryFullName = "example-org/example-repo",
        subjectType = "PullRequest",
        reason = NotificationIgnoreReason.UNSUBSCRIBED,
        ignoredAtEpochMs = 2_000,
    )
    saveIgnoredThread(
        threadId = "thread-2",
        repositoryFullName = "example-org/example-repo",
        subjectType = "Issue",
        reason = NotificationIgnoreReason.DONE,
        ignoredAtEpochMs = 3_000,
        notificationUpdatedAtEpochMs = 2_026_052_910_000,
    )
}

private fun expectedIgnoredThreads(): List<IgnoredNotificationThread> = listOf(
    IgnoredNotificationThread(
        threadId = "thread-1",
        repositoryFullName = "example-org/example-repo",
        subjectType = "PullRequest",
        reason = NotificationIgnoreReason.UNSUBSCRIBED,
        ignoredAtEpochMs = 2_000,
        notificationUpdatedAtEpochMs = null,
    ),
    IgnoredNotificationThread(
        threadId = "thread-2",
        repositoryFullName = "example-org/example-repo",
        subjectType = "Issue",
        reason = NotificationIgnoreReason.DONE,
        ignoredAtEpochMs = 3_000,
        notificationUpdatedAtEpochMs = 2_026_052_910_000,
    ),
)
