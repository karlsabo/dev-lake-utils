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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

private const val PULL_REQUEST_SUBJECT_TYPE = "PullRequest"
private const val NOTIFICATION_CONCURRENCY = 16

private data class NotificationPullRequestDetails(
    val number: Int?,
    val headRef: String?,
)

internal fun ViewModel.notificationsStateFlow(
    gitHubAccess: StateFlow<CommittedGitHubAccess>,
    configs: StateFlow<EngHubConfig>,
    state: EngHubViewModelState,
    persistence: IgnoredNotificationPersistence,
): StateFlow<Result<List<NotificationUiState>>?> {
    val polledNotifications = polledNotifications(
        gitHubAccess = gitHubAccess,
        configs = configs,
        state = state,
        persistence = persistence,
    )

    return combine(polledNotifications, state.ignoredThreads) { result, ignored ->
        result?.map { list -> list.filterNot { ignored.hides(it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS), null)
}

private fun polledNotifications(
    gitHubAccess: StateFlow<CommittedGitHubAccess>,
    configs: StateFlow<EngHubConfig>,
    state: EngHubViewModelState,
    persistence: IgnoredNotificationPersistence,
): Flow<Result<List<NotificationUiState>>?> = combine(configs, gitHubAccess) { config, access ->
    config to access
}.flatMapLatest { (config, access) ->
    if (!access.isReady) {
        flowOf(null)
    } else {
        fixedIntervalPollingFlow(config) {
            runCatching {
                access.services.notificationApi.listNotifications()
                    .filterNot { state.ignoredThreads.value.hides(it) }
                    .asSequence()
                    .asFlow()
                    .flatMapMerge(concurrency = NOTIFICATION_CONCURRENCY) { notif ->
                        processedNotificationFlow(notif, access.services.notificationService, persistence)
                    }
                    .mapNotNull { notif ->
                        access.services.pullRequestReviewApi.toNotificationUiStateOrNull(notif)
                    }
                    .toList()
            }.rethrowCancellation().also { result ->
                result.onFailure { logger.error(it) { "Error polling notifications" } }
            }
        }.flowOn(Dispatchers.IO).clearBeforeFirstEmission()
    }
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
        if (processed.shouldAutoPersistDoneThread()) {
            persistence.persistAutomaticallyDoneThreadOrLog(notif)
        }
    } else {
        emit(notif)
    }
}

private suspend fun GitHubPullRequestReviewApi.toNotificationUiStateOrNull(
    notif: Notification,
): NotificationUiState? {
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

private fun NotificationProcessingResult.Processed.shouldAutoPersistDoneThread(): Boolean {
    val wasClosedOrMerged = wasClosedOrMergedPullRequestDone()
    return wasClosedOrMerged || wasDoneByAutoApprovalWorkflow()
}

private fun NotificationProcessingResult.Processed.wasClosedOrMergedPullRequestDone(): Boolean {
    val wasDone = wasMarkedAsDone()
    return wasDone && pullRequestStatus.isClosedOrMerged()
}

private fun NotificationProcessingResult.Processed.wasDoneByAutoApprovalWorkflow(): Boolean {
    val wasDone = wasMarkedAsDone()
    return wasDone && actions.any { action ->
        action is NotificationAction.ApprovedPullRequest ||
            action is NotificationAction.SkippedApproval
    }
}

private fun NotificationProcessingResult.Processed.wasMarkedAsDone(): Boolean {
    val markDoneAction = actions.any { action -> action is NotificationAction.MarkedAsDone }
    return markDoneAction
}

private fun PullRequestStatus?.isClosedOrMerged(): Boolean {
    val closedOrMergedStatuses = listOf(PullRequestStatus.CLOSED, PullRequestStatus.MERGED)
    return this in closedOrMergedStatuses
}
