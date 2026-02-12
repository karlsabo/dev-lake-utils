package com.github.karlsabo.github

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val logger = KotlinLogging.logger {}

enum class PullRequestStatus {
    OPEN, MERGED, CLOSED, UNKNOWN
}

fun PullRequest.toPullRequestStatus(): PullRequestStatus = when {
    isMerged -> PullRequestStatus.MERGED
    isClosed -> PullRequestStatus.CLOSED
    isOpen -> PullRequestStatus.OPEN
    else -> PullRequestStatus.UNKNOWN
}

sealed interface NotificationAction {
    val description: String

    data class ApprovedPullRequest(override val description: String) : NotificationAction
    data class SkippedApproval(override val description: String) : NotificationAction
    data class MarkedAsDone(override val description: String) : NotificationAction
    data class ActionFailed(override val description: String) : NotificationAction
}

sealed interface NotificationProcessingResult {
    val notification: Notification

    data class Processed(
        override val notification: Notification,
        val pullRequestStatus: PullRequestStatus? = null,
        val pullRequestUrl: String? = null,
        val actions: List<NotificationAction> = emptyList(),
    ) : NotificationProcessingResult

    data class Failed(
        override val notification: Notification,
        val error: String,
    ) : NotificationProcessingResult
}

class GitHubNotificationService(
    private val gitHubApi: GitHubApi,
    private val maxConcurrency: Int = 5,
    private val autoApprovePredicate: (String) -> Boolean = ::defaultAutoApprovePredicate,
    private val approvalMessage: String = "Auto-approving demo appfile update",
) {
    suspend fun processAllNotifications(): List<NotificationProcessingResult> {
        val notifications = gitHubApi.listNotifications()
        logger.info { "Found ${notifications.size} notifications" }

        if (notifications.isEmpty()) return emptyList()

        val semaphore = Semaphore(permits = maxConcurrency)
        return coroutineScope {
            notifications.map { notification ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        processNotification(notification)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun processNotification(notification: Notification): NotificationProcessingResult {
        return try {
            if (notification.isPullRequest) {
                val prUrl = notification.subjectApiUrl
                if (prUrl != null) {
                    processPullRequestNotification(notification, prUrl)
                } else {
                    NotificationProcessingResult.Processed(
                        notification = notification,
                        pullRequestStatus = PullRequestStatus.UNKNOWN,
                    )
                }
            } else {
                NotificationProcessingResult.Processed(notification = notification)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process notification ${notification.id}: ${notification.subject.title}" }
            NotificationProcessingResult.Failed(
                notification = notification,
                error = e.message ?: "Unknown error",
            )
        }
    }

    private suspend fun processPullRequestNotification(
        notification: Notification,
        prUrl: String,
    ): NotificationProcessingResult.Processed {
        val actions = mutableListOf<NotificationAction>()

        val pr = gitHubApi.getPullRequestByUrl(prUrl)
        val status = pr.toPullRequestStatus()

        val title = notification.subject.title

        if (autoApprovePredicate(title) && pr.isOpen) {
            processAutoApproval(pr, prUrl, title, notification, actions)
        }

        if (pr.isMerged || pr.isClosed) {
            markNotificationDone(notification, actions)
        }

        return NotificationProcessingResult.Processed(
            notification = notification,
            pullRequestStatus = status,
            pullRequestUrl = pr.htmlUrl,
            actions = actions,
        )
    }

    private suspend fun processAutoApproval(
        pr: PullRequest,
        prUrl: String,
        title: String,
        notification: Notification,
        actions: MutableList<NotificationAction>,
    ) {
        try {
            val alreadyApproved = gitHubApi.hasAnyApprovedReview(prUrl)
            if (alreadyApproved) {
                actions.add(
                    NotificationAction.SkippedApproval(
                        "PR already approved; skipping additional approval ${pr.url} $title"
                    )
                )
            } else {
                gitHubApi.approvePullRequestByUrl(prUrl, body = approvalMessage)
                actions.add(
                    NotificationAction.ApprovedPullRequest(
                        "Approved PR based on title match ${pr.url} $title"
                    )
                )
            }
            markNotificationDone(notification, actions)
        } catch (e: Exception) {
            actions.add(
                NotificationAction.ActionFailed(
                    "Failed to check/approve PR ${pr.url} $title (${e.message})"
                )
            )
        }
    }

    private suspend fun markNotificationDone(
        notification: Notification,
        actions: MutableList<NotificationAction>,
    ) {
        try {
            gitHubApi.markNotificationAsDone(notification.id)
            actions.add(NotificationAction.MarkedAsDone("Marked notification as done"))
        } catch (e: Exception) {
            actions.add(
                NotificationAction.ActionFailed(
                    "Failed to mark notification as done (${e.message})"
                )
            )
        }
    }
}

private fun defaultAutoApprovePredicate(title: String): Boolean {
    return title.startsWith("Updating appfile", ignoreCase = true)
            && (title.contains("demo", ignoreCase = true)
            || title.contains("to dev", ignoreCase = true))
}
