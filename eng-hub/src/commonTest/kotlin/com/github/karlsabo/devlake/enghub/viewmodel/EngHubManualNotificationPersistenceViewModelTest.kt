package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.notifications.NotificationIgnoreReason
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubManualNotificationPersistenceViewModelTest {

    @Test
    fun donePersistsThreadAndFiltersAfterRestart() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
                .single { it.notificationThreadId == "thread-1" }
        }

        viewModel.markNotificationDone(notificationUiState)

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-1",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
    }

    @Test
    fun doneMarkDoneFailureRestoresVisibilityAndDoesNotPersist() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            markDoneFailure = IllegalStateException("mark done failed"),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
                .single { it.notificationThreadId == "thread-1" }
        }

        viewModel.markNotificationDone(notificationUiState)

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "mark done failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed mark-done should not persist the thread locally")
    }

    @Test
    fun approvePersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/22"
        val notification = testNotification(
            id = "thread-2",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 22,
                    url = pullRequestUrl,
                    head = PullRequestHead(ref = "feature/approve"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-2" } }
                .single { it.notificationThreadId == "thread-2" }
        }

        viewModel.approvePullRequest(notificationUiState)

        assertEquals(listOf(pullRequestUrl), api.approvedPullRequestUrls.awaitValue())
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

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
    }

    @Test
    fun donePersistenceFailureReportsLocalFailureAndRestoresNotificationVisibility() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore(
            saveFailure = IllegalStateException("persist failed"),
        )
        val viewModel = createViewModel(api, store)

        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }

        viewModel.markNotificationDone(testNotificationUiState())

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "persist failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed persistence should not leave a saved local thread")
    }
}
