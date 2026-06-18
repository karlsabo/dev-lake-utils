package com.github.karlsabo.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Instant

class GitHubNotificationServiceTest {
    @Test
    fun processNotification_rethrowsCancellationFromAutoApprovalCleanup() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val service = GitHubNotificationService(
            notificationApi = CancellingMarkDoneNotificationApi(cancellation),
            pullRequestReviewApi = OpenPullRequestReviewApi(),
            autoApprovePredicate = { true },
        )

        val thrown = assertFailsWith<CancellationException> {
            service.processNotification(pullRequestNotification())
        }

        assertSame(cancellation, thrown)
    }
}

private class CancellingMarkDoneNotificationApi(
    private val cancellation: CancellationException,
) : GitHubNotificationApi {
    override suspend fun listNotifications(): List<Notification> = emptyList()

    override suspend fun markNotificationAsDone(threadId: String): Unit = throw cancellation

    override suspend fun unsubscribeFromNotification(threadId: String) = error("Unexpected unsubscribe")
}

private class OpenPullRequestReviewApi : GitHubPullRequestReviewApi {
    override suspend fun getPullRequestByUrl(url: String): PullRequest = PullRequest(
        url = url,
        htmlUrl = "https://github.example/test-org/test-repo/pull/1",
        state = "open",
    )

    override suspend fun approvePullRequestByUrl(url: String, body: String?) = Unit

    override suspend fun hasAnyApprovedReview(url: String): Boolean = false

    override suspend fun submitReview(
        prApiUrl: String,
        event: ReviewStateValue,
        reviewComment: String?,
    ) = error("Unexpected submitReview")
}

private fun pullRequestNotification(): Notification = Notification(
    id = "thread-1",
    unread = true,
    reason = "review_requested",
    updatedAt = Instant.fromEpochMilliseconds(0),
    subject = NotificationSubject(
        title = "Any pull request title",
        url = "https://api.github.example/repos/test-org/test-repo/pulls/1",
        type = "PullRequest",
    ),
    repository = NotificationRepository(
        id = 1,
        name = "test-repo",
        fullName = "test-org/test-repo",
    ),
)
