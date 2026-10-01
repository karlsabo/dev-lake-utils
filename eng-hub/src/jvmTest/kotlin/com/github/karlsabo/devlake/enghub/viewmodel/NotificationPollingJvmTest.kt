package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Notification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class NotificationPollingJvmTest {

    @Test
    fun recoversAfterTransientNotificationListWebFailure() = runBlocking {
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(
                testNotification(
                    id = "thread-1234",
                    subjectType = "Issue",
                    subjectUrl = null,
                ),
            ),
            listNotificationFailuresBeforeSuccess = listOf(UnresolvedAddressException()),
        )
        val failureObserved = CompletableDeferred<Unit>()
        val gatedApi = object : GitHubApi by api {
            override suspend fun listNotifications(): List<Notification> {
                // StateFlow may conflate recovery with failure unless observation is acknowledged.
                if (failureDelivered) failureObserved.await()
                failureDelivered = true
                return api.listNotifications()
            }

            private var failureDelivered = false
        }
        val viewModel = createViewModel(
            api = gatedApi,
            store = RecordingNotificationIgnoreStore(),
            pollIntervalMs = 1,
        )

        try {
            val results = withTimeout(10.seconds) {
                viewModel.notifications.filterNotNull().onEach { result ->
                    if (!failureObserved.isCompleted) {
                        assertIs<UnresolvedAddressException>(result.exceptionOrNull())
                        failureObserved.complete(Unit)
                    }
                }.take(2).toList()
            }

            assertIs<UnresolvedAddressException>(results.first().exceptionOrNull())
            assertEquals(
                listOf("thread-1234"),
                results.last().getOrThrow().map { it.notificationThreadId },
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
