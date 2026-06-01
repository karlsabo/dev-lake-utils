package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.Notification
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
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
            persistIgnoredThread(notification, NotificationIgnoreReason.DONE)
            state.ignoredThreads.update {
                it + (notification.id to notification.toIgnoredNotificationThread(NotificationIgnoreReason.DONE))
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
        persistIgnoredThread(
            threadId = notification.notificationThreadId,
            repositoryFullName = notification.repositoryFullName,
            subjectType = notification.subjectType,
            reason = reason,
            notificationUpdatedAtEpochMs = notification.updatedAtEpochMs,
        )
    }

    private fun persistIgnoredThread(notification: Notification, @Suppress("SameParameterValue") reason: NotificationIgnoreReason) {
        persistIgnoredThread(
            threadId = notification.id,
            repositoryFullName = notification.repository.fullName,
            subjectType = notification.subject.type,
            reason = reason,
            notificationUpdatedAtEpochMs = notification.updatedAt.toEpochMilliseconds(),
        )
    }

    private fun persistIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        notificationUpdatedAtEpochMs: Long?,
    ) {
        notificationIgnoreStore.saveIgnoredThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
        )
    }
}
