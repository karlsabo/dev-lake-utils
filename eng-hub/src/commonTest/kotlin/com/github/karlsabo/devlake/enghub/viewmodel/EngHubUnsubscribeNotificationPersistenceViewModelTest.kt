package com.github.karlsabo.devlake.enghub.viewmodel

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

class EngHubUnsubscribeNotificationPersistenceViewModelTest {

    @Test
    fun unsubscribePersistsThreadAndMarksNotificationDone() = runBlocking {
        val api = NotificationPersistenceGitHubApi()
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notification = testNotificationUiState()

        viewModel.unsubscribeFromNotification(notification)

        assertEquals(listOf("thread-1"), api.unsubscribedThreadIds.awaitValue())
        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-1",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.UNSUBSCRIBED,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )
    }

    @Test
    fun failedUnsubscribeDoesNotPersistThread() = runBlocking {
        val api = NotificationPersistenceGitHubApi(
            unsubscribeFailure = IllegalStateException("boom"),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        viewModel.unsubscribeFromNotification(testNotificationUiState())

        assertEquals(
            "boom",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        assertTrue(store.savedThreads.value.isEmpty(), "Failed unsubscribe should not persist the thread locally")
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Failed unsubscribe should not mark the thread done")
    }

    @Test
    fun failedPersistenceKeepsNotificationVisibleAndDoesNotMarkDone() = runBlocking {
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

        viewModel.unsubscribeFromNotification(testNotificationUiState())

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
        assertEquals(listOf("thread-1"), api.unsubscribedThreadIds.awaitValue())
        assertTrue(store.savedThreads.value.isEmpty(), "Failed persistence should not leave a saved local thread")
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Failed persistence should not mark the thread done")
    }
}
