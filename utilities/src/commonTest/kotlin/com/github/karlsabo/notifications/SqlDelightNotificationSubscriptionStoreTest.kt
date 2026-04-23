package com.github.karlsabo.notifications

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightNotificationSubscriptionStoreTest {

    @Test
    fun savesAndReloadsUnsubscribedThreadIds() {
        val testDir = createNotificationStoreTestDir()
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
