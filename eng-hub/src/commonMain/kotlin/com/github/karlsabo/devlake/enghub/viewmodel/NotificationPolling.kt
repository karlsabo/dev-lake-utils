@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.toNotificationUiState
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.GitHubPullRequestReviewApi
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.NotificationAction
import com.github.karlsabo.github.NotificationProcessingResult
import com.github.karlsabo.github.PullRequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val PULL_REQUEST_SUBJECT_TYPE = "PullRequest"
private const val NOTIFICATION_CONCURRENCY = 16
private const val POLLING_RETRY_COUNT = 5L

private data class NotificationPullRequestDetails(
    val number: Int?,
    val headRef: String?,
)

internal fun ViewModel.notificationsStateFlow(
    gitHubServices: EngHubGitHubServices,
    config: EngHubConfig,
    state: EngHubViewModelState,
    persistence: IgnoredNotificationPersistence,
): StateFlow<Result<List<NotificationUiState>>?> {
    val polledNotifications = polledNotifications(
        gitHubServices = gitHubServices,
        config = config,
        state = state,
        persistence = persistence,
    )

    return combine(polledNotifications, state.ignoredThreads) { result, ignored ->
        result.map { list -> list.filterNot { ignored.hides(it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS), null)
}

private fun polledNotifications(
    gitHubServices: EngHubGitHubServices,
    config: EngHubConfig,
    state: EngHubViewModelState,
    persistence: IgnoredNotificationPersistence,
): Flow<Result<List<NotificationUiState>>> = flow {
    while (true) {
        val uiStates = gitHubServices.notificationApi.listNotifications()
            .filterNot { state.ignoredThreads.value.hides(it) }
            .asSequence()
            .asFlow()
            .flatMapMerge(concurrency = NOTIFICATION_CONCURRENCY) { notif ->
                processedNotificationFlow(notif, gitHubServices.notificationService, persistence)
            }
            .mapNotNull { notif ->
                gitHubServices.pullRequestReviewApi.toNotificationUiStateOrNull(notif)
            }
            .toList()
        emit(Result.success(uiStates))
        delay(config.pollIntervalMs.milliseconds)
    }
}
    .flowOn(Dispatchers.IO)
    .retry(POLLING_RETRY_COUNT) { cause ->
        if (cause.isRetriablePollingFailure()) {
            delay(POLLING_RETRY_DELAY_MS.milliseconds)
            true
        } else {
            false
        }
    }
    .catch { e ->
        logger.error(e) { "Error polling notifications" }
        emit(Result.failure(e))
    }

private fun processedNotificationFlow(
    notif: Notification,
    gitHubNotificationService: GitHubNotificationService,
    persistence: IgnoredNotificationPersistence,
): Flow<Notification> = flow {
    val processed = withContext(Dispatchers.IO) {
        gitHubNotificationService.processNotification(notif)
    } as? NotificationProcessingResult.Processed
    if (processed?.wasMarkedAsDone() == true) {
        if (processed.shouldPersistAutomaticallyDoneThread()) {
            persistence.persistAutomaticallyDoneThreadOrLog(notif)
        }
    } else {
        emit(notif)
    }
}

private suspend fun GitHubPullRequestReviewApi.toNotificationUiStateOrNull(notif: Notification): NotificationUiState? {
    val prDetails = getNotificationPullRequestDetails(
        subjectType = notif.subject.type,
        subjectUrl = notif.subject.url,
    )

    if (notif.subject.type == PULL_REQUEST_SUBJECT_TYPE && prDetails == null) return null

    return notif.toNotificationUiState(
        pullRequestNumber = prDetails?.number,
        headRef = prDetails?.headRef,
    )
}

private suspend fun GitHubPullRequestReviewApi.getNotificationPullRequestDetails(
    subjectType: String,
    subjectUrl: String?,
): NotificationPullRequestDetails? {
    if (subjectType != PULL_REQUEST_SUBJECT_TYPE || subjectUrl == null) return null

    return runCatching {
        withContext(Dispatchers.IO) {
            getPullRequestByUrl(subjectUrl).let { pullRequest ->
                NotificationPullRequestDetails(
                    number = pullRequest.number,
                    headRef = pullRequest.head?.ref,
                )
            }
        }
    }.rethrowCancellation().getOrNull()
}

private fun NotificationProcessingResult.Processed.shouldPersistAutomaticallyDoneThread(): Boolean = wasMarkedDoneForClosedOrMergedPullRequest() || wasMarkedDoneByAutoApprovalWorkflow()

private fun NotificationProcessingResult.Processed.wasMarkedDoneForClosedOrMergedPullRequest(): Boolean = wasMarkedAsDone() && pullRequestStatus.isClosedOrMerged()

private fun NotificationProcessingResult.Processed.wasMarkedDoneByAutoApprovalWorkflow(): Boolean = wasMarkedAsDone() && actions.any {
    it is NotificationAction.ApprovedPullRequest || it is NotificationAction.SkippedApproval
}

private fun NotificationProcessingResult.Processed.wasMarkedAsDone(): Boolean = actions.any { it is NotificationAction.MarkedAsDone }

private fun PullRequestStatus?.isClosedOrMerged(): Boolean = this == PullRequestStatus.CLOSED || this == PullRequestStatus.MERGED
