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

    override fun listIgnoredThreadIds(): Set<String> = queries.selectAllThreadIds().executeAsList().toSet()

    override fun listIgnoredThreads(): List<IgnoredNotificationThread> = queries.selectAll {
            threadId,
            repositoryFullName,
            subjectType,
            ignoreReason,
            ignoredAtEpochMs,
            notificationUpdatedAtEpochMs,
        ->
        IgnoredNotificationThreadRow(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            ignoreReason = ignoreReason,
            ignoredAtEpochMs = ignoredAtEpochMs,
            notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
        ).toIgnoredNotificationThread()
    }.executeAsList()

    override fun saveIgnoredThread(request: SaveIgnoredNotificationThreadRequest) {
        queries.upsertThread(
            thread_id = request.threadId,
            repository_full_name = request.repositoryFullName,
            subject_type = request.subjectType,
            ignore_reason = request.reason.name,
            ignored_at_epoch_ms = request.ignoredAtEpochMs,
            notification_updated_at_epoch_ms = request.notificationUpdatedAtEpochMs,
        )
    }
}

private data class IgnoredNotificationThreadRow(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val ignoreReason: String,
    val ignoredAtEpochMs: Long,
    val notificationUpdatedAtEpochMs: Long?,
)

private fun IgnoredNotificationThreadRow.toIgnoredNotificationThread(): IgnoredNotificationThread {
    val reason = NotificationIgnoreReason.valueOf(ignoreReason)
    return IgnoredNotificationThread(
        threadId = threadId,
        repositoryFullName = repositoryFullName,
        subjectType = subjectType,
        reason = reason,
        ignoredAtEpochMs = ignoredAtEpochMs,
        notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
    )
}
