package com.github.karlsabo.notifications

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import kotlinx.io.files.Path

val engHubNotificationsDatabasePath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "eng-hub-notifications.db")

class SqlDelightNotificationSubscriptionStore(
    driverFactory: NotificationDatabaseDriverFactory = NotificationDatabaseDriverFactory(),
    databasePath: String = engHubNotificationsDatabasePath.toString(),
) : NotificationSubscriptionStore {
    private val queries =
        NotificationDatabase(driverFactory.createDriver(databasePath)).unsubscribedNotificationThreadsQueries

    override fun listUnsubscribedThreadIds(): Set<String> {
        return queries.selectAllThreadIds().executeAsList().toSet()
    }

    override fun saveUnsubscribedThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        unsubscribedAtEpochMs: Long,
    ) {
        queries.upsertThread(
            thread_id = threadId,
            repository_full_name = repositoryFullName,
            subject_type = subjectType,
            unsubscribed_at_epoch_ms = unsubscribedAtEpochMs,
        )
    }
}
