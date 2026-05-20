package com.github.karlsabo.notifications

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import kotlinx.io.files.Path

val engHubNotificationsDatabasePath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "eng-hub-notifications.db")

class SqlDelightNotificationIgnoreStore(
    driverFactory: NotificationDatabaseDriverFactory = NotificationDatabaseDriverFactory(),
    databasePath: String = engHubNotificationsDatabasePath.toString(),
) : NotificationIgnoreStore {
    private val queries =
        NotificationDatabase(driverFactory.createDriver(databasePath)).ignoredNotificationThreadsQueries

    override fun listIgnoredThreadIds(): Set<String> {
        return queries.selectAllThreadIds().executeAsList().toSet()
    }

    override fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
    ) {
        queries.upsertThread(
            thread_id = threadId,
            repository_full_name = repositoryFullName,
            subject_type = subjectType,
            ignore_reason = reason.name,
            ignored_at_epoch_ms = ignoredAtEpochMs,
        )
    }
}
