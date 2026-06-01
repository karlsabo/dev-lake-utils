package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.Notification
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.notifications.SaveIgnoredNotificationThreadRequest
import com.github.karlsabo.notifications.toIgnoredNotificationThread
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock

internal class IgnoredNotificationPersistence(
    private val state: EngHubViewModelState,
    private val notificationIgnoreStore: NotificationIgnoreStore,
) {
    fun persistUnsubscribedThread(notification: NotificationUiState) {
        persistIgnoredThread(notification, NotificationIgnoreReason.UNSUBSCRIBED)
    }

    fun persistDoneThread(notification: NotificationUiState) {
        persistIgnoredThread(notification, NotificationIgnoreReason.DONE)
    }

    fun persistAutomaticallyDoneThreadOrLog(notification: Notification) {
        runCatching {
            val request = notification.toDoneSaveIgnoredThreadRequest()
            notificationIgnoreStore.saveIgnoredThread(request)
            state.ignoredThreads.update {
                it + (request.threadId to request.toIgnoredNotificationThread())
            }
        }.onFailure { failure ->
            logger.error(failure) {
                "Failed to persist automatically done notification ${notification.id}; " +
                    "will retry if GitHub returns it again"
            }
        }
    }

    private fun persistIgnoredThread(
        notification: NotificationUiState,
        reason: NotificationIgnoreReason,
    ) {
        notificationIgnoreStore.saveIgnoredThread(notification.toSaveIgnoredThreadRequest(reason))
    }
}

private fun NotificationUiState.toSaveIgnoredThreadRequest(
    reason: NotificationIgnoreReason,
): SaveIgnoredNotificationThreadRequest = SaveIgnoredNotificationThreadRequest(
    threadId = notificationThreadId,
    repositoryFullName = repositoryFullName,
    subjectType = subjectType,
    reason = reason,
    ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
    notificationUpdatedAtEpochMs = updatedAtEpochMs,
)

private fun Notification.toDoneSaveIgnoredThreadRequest() = SaveIgnoredNotificationThreadRequest(
    threadId = id,
    repositoryFullName = repository.fullName,
    subjectType = subject.type,
    reason = NotificationIgnoreReason.DONE,
    ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
    notificationUpdatedAtEpochMs = updatedAt.toEpochMilliseconds(),
)
