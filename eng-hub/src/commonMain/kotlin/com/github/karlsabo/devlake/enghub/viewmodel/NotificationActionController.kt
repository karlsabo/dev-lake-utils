package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NotificationActionController(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val gitHubServices: EngHubGitHubServices,
    private val persistence: IgnoredNotificationPersistence,
    private val errorReporter: ActionErrorReporter,
) {
    fun approvePullRequest(notification: NotificationUiState) {
        val apiUrl = requireNotNull(notification.apiUrl) { "Cannot approve notification without an API URL" }
        runPullRequestDoneAction(
            notification = notification,
            actionLogName = "approve PR $apiUrl",
            actionFailureMessage = "Failed to approve pull request",
        ) {
            gitHubServices.api.approvePullRequestByUrl(apiUrl)
        }
    }

    fun submitReview(
        notification: NotificationUiState,
        event: ReviewStateValue,
        reviewComment: String?,
    ) {
        val apiUrl = requireNotNull(notification.apiUrl) {
            "Cannot submit review for notification without an API URL"
        }
        runPullRequestDoneAction(
            notification = notification,
            actionLogName = "submit review for $apiUrl",
            actionFailureMessage = "Failed to submit review",
        ) {
            gitHubServices.api.submitReview(apiUrl, event, reviewComment)
        }
    }

    fun markNotificationDone(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        markThreadActingAndIgnored(notification, NotificationIgnoreReason.DONE)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { gitHubServices.api.markNotificationAsDone(notificationThreadId) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to mark notification done $notificationThreadId" }
                    state.ignoredThreads.update { it - notificationThreadId }
                    errorReporter.enqueueActionError(failure.message ?: "Failed to mark notification as done")
                    state.actingOnThreadIds.update { it - notificationThreadId }
                    return@launch
                }
            persistDoneThreadOrReport(notification, "mark notification done $notificationThreadId")
            state.actingOnThreadIds.update { it - notificationThreadId }
        }
    }

    fun unsubscribeFromNotification(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        markThreadActingAndIgnored(notification, NotificationIgnoreReason.UNSUBSCRIBED)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            if (!unsubscribeAndPersist(notification)) return@launch

            runCatching { gitHubServices.api.markNotificationAsDone(notificationThreadId) }
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
        action: suspend () -> Unit,
    ) {
        val notificationThreadId = notification.notificationThreadId
        state.actingOnThreadIds.update { it + notificationThreadId }
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching { action() }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to $actionLogName" }
                    errorReporter.enqueueActionError(failure.message ?: actionFailureMessage)
                    state.actingOnThreadIds.update { it - notificationThreadId }
                    return@launch
                }
            finishPullRequestDoneAction(notification, actionLogName)
            state.actingOnThreadIds.update { it - notificationThreadId }
        }
    }

    private suspend fun finishPullRequestDoneAction(notification: NotificationUiState, actionLogName: String) {
        val notificationThreadId = notification.notificationThreadId
        state.ignoredThreads.update {
            it + (notificationThreadId to notification.toIgnoredNotificationThread(NotificationIgnoreReason.DONE))
        }

        runCatching { gitHubServices.api.markNotificationAsDone(notificationThreadId) }
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

    private suspend fun unsubscribeAndPersist(notification: NotificationUiState): Boolean {
        val notificationThreadId = notification.notificationThreadId
        val unsubscribed = runCatching { gitHubServices.api.unsubscribeFromNotification(notificationThreadId) }
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
