package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRepositoryDiscoveryContractTest {
    @Test
    fun expansionUsesEntriesDiscoveryWithoutStatusDiscovery() = runBlocking {
        val fixture = discoveryFixture()
        try {
            fixture.controller.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
            withTimeout(5_000) {
                fixture.state.localRepositories.first { repositories ->
                    repositories.single().let { it.isExpanded && !it.isLoading }
                }
            }

            assertEquals(listOf(DEV_LAKE_ROOT), fixture.api.listWorktreeEntryRepoPaths)
            assertEquals(emptyList(), fixture.api.listWorktreesWithStatusRepoPaths)
            assertEquals(listOf("main"), fixture.state.localRepositories.value.single().worktrees.map { it.branch })
            assertEquals(null, fixture.state.actionErrors.value.current)
        } finally {
            fixture.viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun initialAndRepeatedPollingUseEntriesDiscoveryWithoutStatusDiscovery() = runTest {
        val fixture = discoveryFixture()
        val pollingJob = backgroundScope.launch { fixture.controller.pollConfiguredLocalRepositoryWorktrees() }
        try {
            runCurrent()
            assertEquals(listOf(DEV_LAKE_ROOT), fixture.api.listWorktreeEntryRepoPaths)

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), fixture.api.listWorktreeEntryRepoPaths)
            assertEquals(emptyList(), fixture.api.listWorktreesWithStatusRepoPaths)
            assertEquals(listOf("main"), fixture.state.localRepositories.value.single().worktrees.map { it.branch })
        } finally {
            pollingJob.cancelAndJoin()
            fixture.viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun recordingApiDistinguishesDiscoveryCallbacksAndRecords() {
        val entryCallbacks = mutableListOf<String>()
        val statusCallbacks = mutableListOf<String>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to emptyList())),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktreeEntries = { entryCallbacks += it },
                onListWorktrees = { statusCallbacks += it },
            ),
        )
        api.listWorktreeEntries(DEV_LAKE_ROOT)
        assertEquals(listOf(DEV_LAKE_ROOT), entryCallbacks)
        assertEquals(emptyList(), statusCallbacks)
        assertEquals(emptyList(), api.listWorktreesWithStatusRepoPaths)

        api.listWorktrees(DEV_LAKE_ROOT)
        assertEquals(listOf(DEV_LAKE_ROOT), entryCallbacks)
        assertEquals(listOf(DEV_LAKE_ROOT), statusCallbacks)
        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeEntryRepoPaths)
        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreesWithStatusRepoPaths)
    }
}

private data class DiscoveryFixture(
    val api: RecordingGitWorktreeApi,
    val state: EngHubViewModelState,
    val viewModel: ViewModel,
    val controller: LocalRepositoryController,
)

private fun discoveryFixture(): DiscoveryFixture {
    val api = RecordingGitWorktreeApi(
        worktreesByRepoPath = mapOf(
            DEV_LAKE_ROOT to listOf(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")),
        ),
    )
    val writer = RecordingEngHubConfigWriter()
    val services = EngHubWorktreeServices(
        gitWorktreeApi = api,
        worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = writer,
    )
    val state = EngHubViewModelState(
        config = EngHubConfig(
            localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT),
            worktreePollIntervalMs = 1_000,
        ),
        configWriter = writer,
        worktreeSetupCoordinator = services.worktreeSetupCoordinator,
        notificationIgnoreStore = NoOpNotificationIgnoreStore(),
    )
    val viewModel = object : ViewModel() {}
    val controller = LocalRepositoryController(viewModel, state, services, ActionErrorReporter(state))
    return DiscoveryFixture(api, state, viewModel, controller)
}
