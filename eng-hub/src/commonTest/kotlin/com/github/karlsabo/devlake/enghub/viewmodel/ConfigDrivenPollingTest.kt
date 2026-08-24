package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.github.GitHubNotificationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDrivenPollingTest {
    @Test
    fun emptyOrganizationsReturnAnEmptyResultWithoutCallingGitHub() = runTest {
        val api = RecordingGitHubApi(emptyMap())

        val result = pullRequestsForConfig(
            searchApi = api,
            reviewApi = api,
            summaryApi = api,
            config = EngHubConfig(organizationIds = emptyList(), gitHubAuthor = "octocat"),
        ).first()

        assertEquals(emptyList(), result.getOrThrow())
        assertEquals(0, api.openPullRequestCalls)
    }

    @Test
    fun committedIntervalStopsObsoletePollingAndStartsReplacementPolling() = runTest {
        val configs = MutableStateFlow(EngHubConfig(pollIntervalMs = 600_000))
        val polls = mutableListOf<Pair<Long, Long>>()

        backgroundScope.launch {
            configDrivenPollingFlow(configs) { config ->
                fixedIntervalPollingFlow(config) {
                    testScheduler.currentTime to config.pollIntervalMs
                }
            }.collect { polls += it }
        }
        runCurrent()

        advanceTimeBy(1_000.milliseconds)
        configs.value = configs.value.copy(pollIntervalMs = 300_000)
        runCurrent()
        advanceTimeBy(300_000.milliseconds)
        runCurrent()
        advanceTimeBy(299_000.milliseconds)
        runCurrent()

        assertEquals(
            listOf(
                0L to 600_000L,
                1_000L to 300_000L,
                301_000L to 300_000L,
            ),
            polls,
        )
    }

    @Test
    fun replacingCommittedAccessClearsActivityUntilTheNewPollCompletes() = runBlocking {
        val initialApi = RecordingGitHubApi(
            pullRequestsByUrl = emptyMap(),
            openPullRequests = emptyList(),
            notifications = emptyList(),
        )
        val replacementPollGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val replacementApi = RecordingGitHubApi(
            pullRequestsByUrl = emptyMap(),
            openPullRequests = emptyList(),
            notifications = emptyList(),
            pollingGate = replacementPollGate,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(),
            configWriter = RecordingEngHubConfigWriter(),
            services = LocalRepositoryViewModelServices(gitHubApi = initialApi),
        )
        viewModel.updateConfig { config ->
            config.copy(organizationIds = listOf("test-org"), gitHubAuthor = "octocat")
        }
        val pullRequestCollector = launch { viewModel.pullRequests.collect {} }
        val notificationCollector = launch { viewModel.notifications.collect {} }

        try {
            awaitGitHubCalls(initialApi)
            withTimeout(2_000) {
                while (viewModel.pullRequests.value == null || viewModel.notifications.value == null) delay(10)
            }

            viewModel.updateGitHubAccess(replacementApi.services(), isReady = true)
            awaitGitHubCalls(replacementApi)

            assertNull(viewModel.pullRequests.value)
            assertNull(viewModel.notifications.value)
        } finally {
            replacementPollGate.complete(Unit)
            pullRequestCollector.cancelAndJoin()
            notificationCollector.cancelAndJoin()
        }
    }

    @Test
    fun revokingAndReenablingCommittedAccessGatesProductionPollingFlows() = runBlocking {
        val initialApi = RecordingGitHubApi(emptyMap())
        val blankTokenApi = RecordingGitHubApi(emptyMap())
        val reenabledApi = RecordingGitHubApi(emptyMap())
        var blankTokenApiCloseCalls = 0
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(),
            configWriter = RecordingEngHubConfigWriter(),
            services = LocalRepositoryViewModelServices(gitHubApi = initialApi),
        )
        viewModel.updateConfig { config ->
            config.copy(
                organizationIds = listOf("test-org"),
                gitHubAuthor = "octocat",
                pollIntervalMs = 25,
            )
        }
        val pullRequestCollector = launch { viewModel.pullRequests.collect {} }
        val notificationCollector = launch { viewModel.notifications.collect {} }

        try {
            awaitGitHubCalls(initialApi)

            viewModel.updateGitHubAccess(
                blankTokenApi.services { blankTokenApiCloseCalls++ },
                isReady = false,
            )
            assertEquals(0, blankTokenApiCloseCalls)
            delay(100)
            val callsAfterRevocation = initialApi.totalPollingCalls()
            delay(100)

            assertEquals(callsAfterRevocation, initialApi.totalPollingCalls())
            assertEquals(0, blankTokenApi.totalPollingCalls())

            viewModel.updateGitHubAccess(reenabledApi.services(), isReady = true)
            assertEquals(1, blankTokenApiCloseCalls)
            awaitGitHubCalls(reenabledApi)
            assertEquals(0, blankTokenApi.totalPollingCalls())
        } finally {
            pullRequestCollector.cancelAndJoin()
            notificationCollector.cancelAndJoin()
        }
    }

    @Test
    fun retiredServicesCloseOnlyAfterTheirActiveActionCompletes() = runTest {
        val api = RecordingGitHubApi(emptyMap())
        var closeCalls = 0
        val services = api.services { closeCalls += 1 }
        val tracker = GitHubServiceActionTracker(backgroundScope)
        val actionGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        val action = tracker.launch(services) { actionGate.await() }
        tracker.retire(services)
        runCurrent()

        assertEquals(0, closeCalls)
        actionGate.complete(Unit)
        action.join()
        assertEquals(1, closeCalls)

        tracker.retire(services)
        assertEquals(1, closeCalls)
    }

    @Test
    fun committedWorktreeIntervalReplacesTheObsoletePollingDelay() = runTest {
        val configs = MutableStateFlow(EngHubConfig(worktreePollIntervalMs = 120_000))
        val pollTimes = mutableListOf<Long>()

        backgroundScope.launch {
            worktreePollingFlow(configs) { testScheduler.currentTime }
                .collect { pollTimes += it }
        }
        runCurrent()

        advanceTimeBy(10_000.milliseconds)
        configs.value = configs.value.copy(worktreePollIntervalMs = 60_000)
        runCurrent()
        advanceTimeBy(59_999.milliseconds)
        runCurrent()
        assertEquals(emptyList(), pollTimes)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        advanceTimeBy(60_000.milliseconds)
        runCurrent()

        assertEquals(listOf(70_000L, 130_000L), pollTimes)
    }

    private suspend fun awaitGitHubCalls(api: RecordingGitHubApi) {
        withTimeout(2_000) {
            while (api.openPullRequestCalls == 0 || api.notificationListCalls == 0) delay(10)
        }
    }

    private fun RecordingGitHubApi.services(
        closeServices: () -> Unit = {},
    ) = EngHubGitHubServices(
        pullRequestSearchApi = this,
        notificationApi = this,
        pullRequestReviewApi = this,
        pullRequestSummaryApi = this,
        notificationService = GitHubNotificationService(this),
        closeServices = closeServices,
    )

    private fun RecordingGitHubApi.totalPollingCalls(): Int = openPullRequestCalls + notificationListCalls
}
