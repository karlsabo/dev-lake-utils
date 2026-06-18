package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class EngHubNotificationRevivalPersistenceViewModelTest {

    @Test
    fun showsNewGitHubActivityOnPreviouslyDoneThread() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val notification = testNotification(
            id = "thread-1",
            title = "Please review feature",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
            updatedAt = Instant.parse("2026-05-29T10:05:00Z"),
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1,
                    url = pullRequestUrl,
                    state = "open",
                    head = PullRequestHead(ref = "feature/revived"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            initialIgnoredThreads = listOf(
                ignoredThread(
                    threadId = "thread-1",
                    reason = NotificationIgnoreReason.DONE,
                    notificationUpdatedAtEpochMs = Instant.parse("2026-05-29T10:00:00Z").toEpochMilliseconds(),
                ),
            ),
        )
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertEquals(listOf("thread-1"), notifications.map { it.notificationThreadId })
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Open non-auto-approvable notifications should be shown")
    }

    @Test
    fun keepsUnsubscribedThreadHiddenAcrossNewGitHubActivity() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/3"
        val notification = testNotification(
            id = "thread-3",
            title = "New activity after unsubscribe",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
            updatedAt = Instant.parse("2026-05-29T10:05:00Z"),
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore(
            initialIgnoredThreads = listOf(
                ignoredThread(
                    threadId = "thread-3",
                    reason = NotificationIgnoreReason.UNSUBSCRIBED,
                    notificationUpdatedAtEpochMs = Instant.parse("2026-05-29T10:00:00Z").toEpochMilliseconds(),
                ),
            ),
        )
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertTrue(
            api.pullRequestByUrlCalls.isEmpty(),
            "Unsubscribed notifications should stay hidden before enrichment",
        )
    }

    @Test
    fun reMarksAutomaticallyHandledRevivedDoneThreadAndUpdatesWatermark() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/2"
        val notification = testNotification(
            id = "thread-2",
            title = "Merged feature",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
            updatedAt = Instant.parse("2026-05-29T10:05:00Z"),
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 2,
                    url = pullRequestUrl,
                    state = "closed",
                    mergedAt = Instant.parse("2026-05-29T10:04:00Z"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            initialIgnoredThreads = listOf(
                ignoredThread(
                    threadId = "thread-2",
                    reason = NotificationIgnoreReason.DONE,
                    notificationUpdatedAtEpochMs = Instant.parse("2026-05-29T10:00:00Z").toEpochMilliseconds(),
                ),
            ),
        )
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-2"), api.markedDoneThreadIds.awaitValue())
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-2",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)
    }
}
