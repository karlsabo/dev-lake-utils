package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.flow.filterNotNull
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
        val viewModel = createViewModel(
            api = api,
            store = RecordingNotificationIgnoreStore(),
            pollIntervalMs = 1,
        )

        val results = withTimeout(2.seconds) {
            viewModel.notifications.filterNotNull().take(2).toList()
        }

        assertIs<UnresolvedAddressException>(results.first().exceptionOrNull())
        assertEquals(
            listOf("thread-1234"),
            results.last().getOrThrow().map { it.notificationThreadId },
        )
    }
}
