package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.coroutines.flow.update

internal class NotificationActionController(
    private val state: EngHubViewModelState,
    private val launchGitHubAction: (suspend (EngHubGitHubServices) -> Unit) -> Unit,
    private val persistence: IgnoredNotificationPersistence,
    private val errorReporter: ActionErrorReporter,
) {
    fun approvePullRequest(notification: NotificationUiState) {
        val apiUrl = requireNotNull(notification.apiUrl) { "Cannot approve notification without an API URL" }
        runPullRequestDoneAction(
            notification = notification,
            actionLogName = "approve PR $apiUrl",
            actionFailureMessage = "Failed to approve pull request",
        ) { services ->
            services.pullRequestReviewApi.approvePullRequestByUrl(apiUrl)
        }
    }

    fun markNotificationDone(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        markThreadActingAndIgnored(notification, NotificationIgnoreReason.DONE)
        launchGitHubAction action@{ services ->
            runCatching { services.notificationApi.markNotificationAsDone(notificationThreadId) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to mark notification done $notificationThreadId" }
                    state.ignoredThreads.update { it - notificationThreadId }
                    errorReporter.enqueueActionError(failure.message ?: "Failed to mark notification as done")
                    state.actingOnThreadIds.update { it - notificationThreadId }
                    return@action
                }
            persistDoneThreadOrReport(notification, "mark notification done $notificationThreadId")
            state.actingOnThreadIds.update { it - notificationThreadId }
        }
    }

    fun unsubscribeFromNotification(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        markThreadActingAndIgnored(notification, NotificationIgnoreReason.UNSUBSCRIBED)
        launchGitHubAction action@{ services ->
            if (!unsubscribeAndPersist(notification, services)) return@action

            runCatching { services.notificationApi.markNotificationAsDone(notificationThreadId) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to mark unsubscribed notification done $notificationThreadId" }
                    errorReporter.enqueueActionError(failure.message ?: "Failed to mark notification as done")
                }
            state.actingOnThreadIds.update { it - notificationThreadId }
        }
    }

    private fun runPullRequestDoneAction(
        notification: NotificationUiState,
        actionLogName: String,
        actionFailureMessage: String,
        action: suspend (EngHubGitHubServices) -> Unit,
    ) {
        val notificationThreadId = notification.notificationThreadId
        state.actingOnThreadIds.update { it + notificationThreadId }
        launchGitHubAction trackedAction@{ services ->
            runCatching { action(services) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to $actionLogName" }
                    errorReporter.enqueueActionError(failure.message ?: actionFailureMessage)
                    state.actingOnThreadIds.update { it - notificationThreadId }
                    return@trackedAction
                }
            finishPullRequestDoneAction(notification, actionLogName, services)
            state.actingOnThreadIds.update { it - notificationThreadId }
        }
    }

    private suspend fun finishPullRequestDoneAction(
        notification: NotificationUiState,
        actionLogName: String,
        services: EngHubGitHubServices,
    ) {
        val notificationThreadId = notification.notificationThreadId
        state.ignoredThreads.update {
            it + (notificationThreadId to notification.toIgnoredNotificationThread(NotificationIgnoreReason.DONE))
        }

        runCatching { services.notificationApi.markNotificationAsDone(notificationThreadId) }
            .onFailure { failure ->
                logger.error(failure) {
                    "Failed to mark notification done $notificationThreadId after $actionLogName"
                }
                state.ignoredThreads.update { it - notificationThreadId }
                errorReporter.enqueueActionError(failure.message ?: "Failed to mark notification as done")
                return
            }

        persistDoneThreadOrReport(notification, actionLogName)
    }

    private fun persistDoneThreadOrReport(notification: NotificationUiState, actionLogName: String) {
        val notificationThreadId = notification.notificationThreadId
        runCatching { persistence.persistDoneThread(notification) }
            .onFailure { failure ->
                logger.error(failure) {
                    "Failed to persist done notification $notificationThreadId after $actionLogName"
                }
                state.ignoredThreads.update { it - notificationThreadId }
                errorReporter.enqueueActionError(
                    failure.message ?: "Failed to persist done notification locally",
                )
            }
    }

    private suspend fun unsubscribeAndPersist(
        notification: NotificationUiState,
        services: EngHubGitHubServices,
    ): Boolean {
        val notificationThreadId = notification.notificationThreadId
        val unsubscribed = runCatching {
            services.notificationApi.unsubscribeFromNotification(notificationThreadId)
        }
            .onFailure { failure ->
                logger.error(failure) { "Failed to unsubscribe from notification $notificationThreadId" }
                rollbackActingIgnoredThread(notificationThreadId)
                errorReporter.enqueueActionError(failure.message ?: "Failed to unsubscribe from notification")
            }
            .isSuccess
        if (!unsubscribed) return false

        return runCatching { persistence.persistUnsubscribedThread(notification) }
            .onFailure { failure ->
                logger.error(failure) { "Failed to persist unsubscribed notification $notificationThreadId" }
                rollbackActingIgnoredThread(notificationThreadId)
                errorReporter.enqueueActionError(
                    failure.message ?: "Failed to persist unsubscribed notification locally",
                )
            }
            .isSuccess
    }

    private fun markThreadActingAndIgnored(
        notification: NotificationUiState,
        reason: NotificationIgnoreReason,
    ) {
        val notificationThreadId = notification.notificationThreadId
        state.actingOnThreadIds.update { it + notificationThreadId }
        state.ignoredThreads.update {
            it + (notificationThreadId to notification.toIgnoredNotificationThread(reason))
        }
    }

    private fun rollbackActingIgnoredThread(notificationThreadId: String) {
        state.ignoredThreads.update { it - notificationThreadId }
        state.actingOnThreadIds.update { it - notificationThreadId }
    }
}
