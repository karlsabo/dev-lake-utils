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

data class SaveIgnoredNotificationThreadRequest(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val reason: NotificationIgnoreReason,
    val ignoredAtEpochMs: Long,
    val notificationUpdatedAtEpochMs: Long? = null,
)

fun SaveIgnoredNotificationThreadRequest.toIgnoredNotificationThread() = IgnoredNotificationThread(
    threadId = threadId,
    repositoryFullName = repositoryFullName,
    subjectType = subjectType,
    reason = reason,
    ignoredAtEpochMs = ignoredAtEpochMs,
    notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
)

interface NotificationIgnoreStore {
    fun listIgnoredThreadIds(): Set<String>

    fun listIgnoredThreads(): List<IgnoredNotificationThread>

    fun saveIgnoredThread(request: SaveIgnoredNotificationThreadRequest)
}
