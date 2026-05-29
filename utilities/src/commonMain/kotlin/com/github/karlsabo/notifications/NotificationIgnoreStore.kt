package com.github.karlsabo.notifications

enum class NotificationIgnoreReason {
    UNSUBSCRIBED,
    DONE,
}

data class IgnoredNotificationThread(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val reason: NotificationIgnoreReason,
    val ignoredAtEpochMs: Long,
    val notificationUpdatedAtEpochMs: Long?,
)

interface NotificationIgnoreStore {
    fun listIgnoredThreadIds(): Set<String>

    fun listIgnoredThreads(): List<IgnoredNotificationThread>

    fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
    ) {
        saveIgnoredThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = ignoredAtEpochMs,
            notificationUpdatedAtEpochMs = null,
        )
    }

    fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
        notificationUpdatedAtEpochMs: Long?,
    )
}
