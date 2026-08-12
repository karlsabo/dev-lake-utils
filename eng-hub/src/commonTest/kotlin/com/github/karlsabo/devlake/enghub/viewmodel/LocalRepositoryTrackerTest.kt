package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRepositoryExpansionTrackerTest {
    @Test
    fun startAssignsIdentityOwnershipAndDoesNotReplaceAnActiveExpansion() {
        val state = trackerState()
        val tracker = LocalRepositoryExpansionTracker(state)

        assertNotNull(tracker.start(DEV_LAKE_ROOT))

        assertEquals(
            RepositorySnapshot(isExpanded = true, isLoading = true),
            state.repositorySnapshot(),
        )
        val activeExpansion = state.repositorySnapshot()
        assertNull(tracker.start(DEV_LAKE_ROOT))
        assertEquals(activeExpansion, state.repositorySnapshot())
    }

    @Test
    fun collapsePermanentlyInvalidatesTheOwnedRequest() {
        val state = trackerState()
        val tracker = LocalRepositoryExpansionTracker(state)
        val oldRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))

        tracker.collapse(DEV_LAKE_ROOT)
        val collapsed = state.repositorySnapshot()

        assertFalse(tracker.publishDiscovered(DEV_LAKE_ROOT, oldRequest, listOf(worktree("late-discovery"))))
        assertEquals(collapsed, state.repositorySnapshot())
        assertFalse(tracker.complete(DEV_LAKE_ROOT, oldRequest, listOf(worktree("late-completion"))))
        assertEquals(collapsed, state.repositorySnapshot())

        val newRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        assertTrue(tracker.complete(DEV_LAKE_ROOT, newRequest, listOf(worktree("current"))))
        assertEquals(
            RepositorySnapshot(isExpanded = true, isLoading = false, branches = listOf("current")),
            state.repositorySnapshot(),
        )
    }

    @Test
    fun refreshOwnershipPreventsExpansionFromPublishingOrCompleting() {
        val state = trackerState()
        val expansionTracker = LocalRepositoryExpansionTracker(state)
        val refreshTracker = LocalRepositoryRefreshTracker(state)
        val expansionRequest = assertNotNull(expansionTracker.start(DEV_LAKE_ROOT))

        val refreshRequest = assertNotNull(refreshTracker.start(DEV_LAKE_ROOT))

        val refreshing = state.repositorySnapshot()
        assertFalse(expansionTracker.publishDiscovered(DEV_LAKE_ROOT, expansionRequest, listOf(worktree("late"))))
        assertEquals(refreshing, state.repositorySnapshot())
        assertFalse(expansionTracker.complete(DEV_LAKE_ROOT, expansionRequest, listOf(worktree("late"))))
        assertEquals(refreshing, state.repositorySnapshot())
        assertTrue(refreshTracker.publishDiscovered(DEV_LAKE_ROOT, refreshRequest, listOf(worktree("refresh"))))
        assertEquals(listOf("refresh"), state.repositorySnapshot().branches)
        assertTrue(refreshTracker.complete(DEV_LAKE_ROOT, refreshRequest, listOf(worktree("refresh"))))
        assertEquals(listOf("refresh"), state.repositorySnapshot().branches)
    }
}

class LocalRepositoryRefreshTrackerTest {
    @Test
    fun newerRefreshIdentityRejectsEveryCommitFromTheOlderRequest() {
        val state = trackerState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val oldRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val newRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))

        val newerRefresh = state.repositorySnapshot()
        assertFalse(tracker.publishDiscovered(DEV_LAKE_ROOT, oldRequest, listOf(worktree("old-discovery"))))
        assertEquals(newerRefresh, state.repositorySnapshot())
        assertFalse(tracker.complete(DEV_LAKE_ROOT, oldRequest, listOf(worktree("old-complete"))))
        assertEquals(newerRefresh, state.repositorySnapshot())
        assertFalse(tracker.fail(DEV_LAKE_ROOT, oldRequest))
        assertEquals(newerRefresh, state.repositorySnapshot())
        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, newRequest, listOf(worktree("new"))))
        assertEquals(listOf("new"), state.repositorySnapshot().branches)
    }

    @Test
    fun failureOnlyClearsLoadingAndOwnershipForTheCurrentRequest() {
        val state = trackerState().also {
            it.localRepositories.value = it.localRepositories.value.map { repository ->
                repository.copy(isExpanded = true, isLoading = true, worktrees = listOf(worktree("retained")))
            }
        }
        val tracker = LocalRepositoryRefreshTracker(state)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))

        assertTrue(tracker.fail(DEV_LAKE_ROOT, request))

        assertEquals(
            RepositorySnapshot(isExpanded = true, isLoading = false, branches = listOf("retained")),
            state.repositorySnapshot(),
        )
        val failed = state.repositorySnapshot()
        assertFalse(tracker.fail(DEV_LAKE_ROOT, request))
        assertEquals(failed, state.repositorySnapshot())
    }

    @Test
    fun missingRepositoryCannotAcquireOrUseOwnership() {
        val state = trackerState()
        val expansionTracker = LocalRepositoryExpansionTracker(state)
        val refreshTracker = LocalRepositoryRefreshTracker(state)
        val unownedRequest = LocalRepositoryWorktreeRequest()

        assertNull(expansionTracker.start("/missing"))
        assertNull(refreshTracker.start("/missing"))
        assertFalse(expansionTracker.publishDiscovered("/missing", unownedRequest, emptyList()))
        assertFalse(expansionTracker.complete("/missing", unownedRequest))
        assertFalse(refreshTracker.publishDiscovered("/missing", unownedRequest, emptyList()))
        assertFalse(refreshTracker.complete("/missing", unownedRequest, emptyList()))
        assertFalse(refreshTracker.fail("/missing", unownedRequest))
    }
}

private fun trackerState(): EngHubViewModelState {
    val api = RecordingGitWorktreeApi()
    val configWriter = RecordingEngHubConfigWriter()
    return EngHubViewModelState(
        config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
        configWriter = configWriter,
        worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
        notificationIgnoreStore = NoOpNotificationIgnoreStore(),
    )
}

private data class RepositorySnapshot(
    val isExpanded: Boolean,
    val isLoading: Boolean,
    val branches: List<String> = emptyList(),
)

private fun EngHubViewModelState.repositorySnapshot(): RepositorySnapshot {
    val repository = localRepositories.value.single()
    return RepositorySnapshot(
        isExpanded = repository.isExpanded,
        isLoading = repository.isLoading,
        branches = repository.worktrees.map { it.branch },
    )
}

private fun worktree(branch: String) = LocalWorktreeUiState(
    branch = branch,
    path = "$DEV_LAKE_ROOT/$branch",
)
