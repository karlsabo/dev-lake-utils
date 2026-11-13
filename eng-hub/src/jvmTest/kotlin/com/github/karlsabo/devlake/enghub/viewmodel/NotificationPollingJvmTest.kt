package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NotificationPollingJvmTest {

    @Test
    fun oldestNotificationsRemainFirstAcrossDifferingProcessingCompletionOrders() = runBlocking {
        val notifications = acceptanceTestNotifications()
        // The exposed state is a StateFlow, which skips equal values, so the second refresh flips a field that does
        // not affect ordering to guarantee its result is observed instead of conflated with the first.
        val secondPollNotifications = notifications.reversed().map { it.copy(unread = false) }
        val api = CompletionOrderGitHubApi(
            notificationPolls = listOf(notifications, secondPollNotifications),
            completionOrders = listOf(
                listOf("thread-400", "thread-200", "thread-300", "thread-100"),
                listOf("thread-300", "thread-100", "thread-400", "thread-200"),
            ),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore(), pollIntervalMs = 1)
        val expectedThreadIds = listOf("thread-300", "thread-100", "thread-200", "thread-400")
        val refreshes = Channel<Result<List<NotificationUiState>>>(Channel.UNLIMITED)
        // The fake holds every poll until released, so each published refresh is observed exactly once.
        val collector = launch { viewModel.notifications.filterNotNull().collect { refreshes.send(it) } }

        try {
            api.allowPoll(index = 0)
            val firstRefresh = withTimeout(10.seconds) { refreshes.receive() }
            assertEquals(expectedThreadIds, firstRefresh.getOrThrow().map { it.notificationThreadId })

            api.allowPoll(index = 1)
            val secondRefresh = withTimeout(10.seconds) { refreshes.receive() }
            assertEquals(expectedThreadIds, secondRefresh.getOrThrow().map { it.notificationThreadId })
        } finally {
            collector.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

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

private fun acceptanceTestNotifications(): List<Notification> = listOf(
    acceptanceNotification(threadId = "thread-300", title = "Old alert", updatedAt = "2026-05-29T09:00:00Z"),
    acceptanceNotification(threadId = "thread-200", title = "Same-time B", updatedAt = "2026-05-29T11:00:00Z"),
    acceptanceNotification(threadId = "thread-100", title = "Same-time A", updatedAt = "2026-05-29T11:00:00Z"),
    acceptanceNotification(threadId = "thread-400", title = "New alert", updatedAt = "2026-05-29T13:00:00Z"),
)

private fun acceptanceNotification(
    threadId: String,
    title: String,
    updatedAt: String,
): Notification = testNotification(
    id = threadId,
    subjectType = "PullRequest",
    subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/${threadId.removePrefix("thread-")}",
    title = title,
    updatedAt = Instant.parse(updatedAt),
)

/**
 * Forces the processing completion order of each poll by chaining every notification's enrichment lookup behind
 * the previous notification's lookup, and holds each poll open until [allowPoll] is called.
 */
private class CompletionOrderGitHubApi private constructor(
    private val delegate: NotificationPersistenceGitHubApi,
    private val notificationPolls: List<List<Notification>>,
    private val completionOrders: List<List<String>>,
) : GitHubApi by delegate {
    constructor(
        notificationPolls: List<List<Notification>>,
        completionOrders: List<List<String>>,
    ) : this(
        delegate = NotificationPersistenceGitHubApi(
            pullRequestsByUrl = notificationPolls.flatten()
                .mapNotNull { notification -> notification.subject.url }
                .associateWith { url -> PullRequest(url = url) },
        ),
        notificationPolls = notificationPolls,
        completionOrders = completionOrders,
    )

    private val pollGates = completionOrders.indices.associateWith { CompletableDeferred<Unit>() }
    private val pollsStarted = AtomicInteger()

    @Volatile
    private var ordering: PollOrderingState? = null

    fun allowPoll(index: Int) {
        pollGates.getValue(index).complete(Unit)
    }

    override suspend fun listNotifications(): List<Notification> {
        val pollIndex = pollsStarted.getAndIncrement()
        // Cycle both input order and processing completion order so consecutive refreshes differ.
        ordering = PollOrderingState(completionOrders[pollIndex % completionOrders.size])
        pollGates[pollIndex]?.await()
        return notificationPolls[pollIndex % notificationPolls.size]
    }

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        ordering?.awaitTurn(url)
        return delegate.getPullRequestByUrl(url)
    }
}

private class PollOrderingState(
    private val completionOrder: List<String>,
) {
    private val callsByUrl = ConcurrentHashMap<String, AtomicInteger>()
    private val enrichmentArrivals = completionOrder.associateWith { CompletableDeferred<Unit>() }

    suspend fun awaitTurn(url: String) {
        val isProcessingCall = callsByUrl.computeIfAbsent(url) { AtomicInteger() }.getAndIncrement() == 0
        if (isProcessingCall) {
            val position = completionOrder.indexOf(url)
            if (position > 0) enrichmentArrivals.getValue(completionOrder[position - 1]).await()
        } else {
            enrichmentArrivals[url]?.complete(Unit)
        }
    }
}
