package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubNotificationEnrichmentViewModelTest {

    @Test
    fun enrichesPullRequestNotificationsWithNumberAndDisplayTitle() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1234"
        val notification = testNotification(
            id = "thread-1234",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1234,
                    url = pullRequestUrl,
                    head = PullRequestHead(ref = "feature/pr-number"),
                ),
            ),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }
        val uiState = notifications.single()

        assertEquals(1234, uiState.pullRequestNumber)
        assertEquals("#1234 Notification thread-1234", uiState.displayTitle)
        assertEquals("feature/pr-number", uiState.headRef)
    }

    @Test
    fun filtersOutPullRequestNotificationsWhenDetailLookupFails() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/9999"
        val notification = testNotification(
            id = "thread-9999",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestFailureUrls = setOf(pullRequestUrl),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertTrue(api.pullRequestByUrlCalls.contains(pullRequestUrl))
    }

    @Test
    fun mixedNotificationBatchDropsOnlyFailedPullRequestNotification() = runBlocking {
        val successfulPullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1234"
        val failedPullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/9999"
        val issueUrl = "https://api.github.com/repos/test-org/test-repo/issues/77"
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(
                testNotification(
                    id = "thread-1234",
                    subjectType = "PullRequest",
                    subjectUrl = successfulPullRequestUrl,
                ),
                testNotification(
                    id = "thread-9999",
                    subjectType = "PullRequest",
                    subjectUrl = failedPullRequestUrl,
                ),
                testNotification(
                    id = "thread-issue",
                    subjectType = "Issue",
                    subjectUrl = issueUrl,
                ),
            ),
            pullRequestsByUrl = mapOf(
                successfulPullRequestUrl to PullRequest(
                    number = 1234,
                    url = successfulPullRequestUrl,
                    head = PullRequestHead(ref = "feature/pr-number"),
                ),
            ),
            pullRequestFailureUrls = setOf(failedPullRequestUrl),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }
        val notificationsById = notifications.associateBy { it.notificationThreadId }

        assertEquals(setOf("thread-1234", "thread-issue"), notificationsById.keys)
        assertEquals(setOf(successfulPullRequestUrl, failedPullRequestUrl), api.pullRequestByUrlCalls.toSet())

        val successfulPullRequest = notificationsById.getValue("thread-1234")
        assertEquals(1234, successfulPullRequest.pullRequestNumber)
        assertEquals("#1234 Notification thread-1234", successfulPullRequest.displayTitle)
        assertEquals("feature/pr-number", successfulPullRequest.headRef)

        val issueNotification = notificationsById.getValue("thread-issue")
        assertEquals("Notification thread-issue", issueNotification.title)
        assertEquals("Notification thread-issue", issueNotification.displayTitle)
        assertEquals("Issue", issueNotification.subjectType)
        assertEquals(issueUrl, issueNotification.apiUrl)
        assertEquals(false, issueNotification.isPullRequest)
        assertEquals(null, issueNotification.pullRequestNumber)
        assertEquals(null, issueNotification.headRef)
    }

    @Test
    fun loadsPersistedIgnoredThreadIdsBeforeNotificationEnrichment() = runBlocking {
        val hiddenNotification = testNotification(
            id = "thread-hidden",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val visibleNotification = testNotification(
            id = "thread-visible",
            subjectType = "Issue",
            subjectUrl = null,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(hiddenNotification, visibleNotification),
        )
        val store = RecordingNotificationIgnoreStore(initialThreadIds = setOf("thread-hidden"))
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertEquals(listOf("thread-visible"), notifications.map { it.notificationThreadId })
        assertTrue(api.pullRequestByUrlCalls.isEmpty(), "Hidden notifications should be filtered before enrichment")
    }
}
