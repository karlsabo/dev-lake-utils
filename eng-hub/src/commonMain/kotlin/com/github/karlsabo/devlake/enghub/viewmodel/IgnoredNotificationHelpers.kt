package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.Notification
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.datetime.Clock

internal fun Map<String, IgnoredNotificationThread>.hides(
    notification: Notification,
): Boolean = this[notification.id]?.hides(notification) == true

internal fun Map<String, IgnoredNotificationThread>.hides(
    notification: NotificationUiState,
): Boolean = this[notification.notificationThreadId]?.hides(notification) == true

private fun IgnoredNotificationThread.hides(
    notification: Notification,
): Boolean = hides(notification.updatedAt.toEpochMilliseconds())

private fun IgnoredNotificationThread.hides(
    notification: NotificationUiState,
): Boolean = hides(notification.updatedAtEpochMs)

private fun IgnoredNotificationThread.hides(notificationUpdatedAtEpochMs: Long): Boolean = when (reason) {
    NotificationIgnoreReason.UNSUBSCRIBED -> true

    NotificationIgnoreReason.DONE ->
        this.notificationUpdatedAtEpochMs
            ?.let { it >= notificationUpdatedAtEpochMs } == true
}

internal fun NotificationUiState.toIgnoredNotificationThread(
    reason: NotificationIgnoreReason,
): IgnoredNotificationThread = IgnoredNotificationThread(
    threadId = notificationThreadId,
    repositoryFullName = repositoryFullName,
    subjectType = subjectType,
    reason = reason,
    ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
    notificationUpdatedAtEpochMs = updatedAtEpochMs,
)
