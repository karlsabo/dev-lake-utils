package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubAutomaticNotificationCleanupViewModelTest {

    @Test
    fun automaticClosedPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
            PullRequest(
                number = 24,
                url = "https://api.github.com/repos/test-org/test-repo/pulls/24",
                state = "closed",
            ),
        )
    }

    @Test
    fun automaticMergedPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
            PullRequest(
                number = 24,
                url = "https://api.github.com/repos/test-org/test-repo/pulls/24",
                state = "closed",
                mergedAt = Instant.parse("2026-05-20T00:00:00Z"),
            ),
        )
    }

    private suspend fun assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
        pullRequest: PullRequest,
    ) {
        val pullRequestUrl = requireNotNull(pullRequest.url)
        val notification = testNotification(
            id = "thread-4",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(pullRequestUrl to pullRequest),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-4"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-4",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
        assertEquals(listOf("thread-4"), api.markedDoneThreadIds.value)
    }

    @Test
    fun automaticAppfileApprovalPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
            threadId = "thread-5",
            pullRequestNumber = 25,
            alreadyApproved = false,
        )
    }

    @Test
    fun alreadyApprovedAutomaticAppfilePersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
            threadId = "thread-6",
            pullRequestNumber = 26,
            alreadyApproved = true,
        )
    }

    private suspend fun assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
        threadId: String,
        pullRequestNumber: Int,
        alreadyApproved: Boolean,
    ) {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/$pullRequestNumber"
        val notification = testNotification(
            id = threadId,
            title = "Updating appfile demo service",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val expectedApprovedPullRequestUrls = if (alreadyApproved) emptyList() else listOf(pullRequestUrl)
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = pullRequestNumber,
                    url = pullRequestUrl,
                    state = "open",
                ),
            ),
            approvedReviewUrls = if (alreadyApproved) setOf(pullRequestUrl) else emptySet(),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf(threadId), api.markedDoneThreadIds.awaitValue())
        assertEquals(expectedApprovedPullRequestUrls, api.approvedPullRequestUrls.value)
        assertEquals(
            listOf(
                SavedThread(
                    threadId = threadId,
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
        assertEquals(expectedApprovedPullRequestUrls, api.approvedPullRequestUrls.value)
        assertEquals(listOf(threadId), api.markedDoneThreadIds.value)
    }

    @Test
    fun automaticDonePersistenceFailureIsLogOnlyAndRetriesOnLaterPoll() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/7"
        val notification = testNotification(
            id = "thread-7",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 7,
                    url = pullRequestUrl,
                    state = "closed",
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            saveFailuresBeforeSuccess = listOf(IllegalStateException("persist failed")),
        )
        val viewModel = createViewModel(api, store, pollIntervalMs = 50)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-7", "thread-7"), api.markedDoneThreadIds.awaitSize(2))
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-7",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }
}
