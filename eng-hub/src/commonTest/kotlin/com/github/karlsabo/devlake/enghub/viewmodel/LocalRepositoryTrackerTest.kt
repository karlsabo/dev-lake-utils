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
    fun publishDiscoveredStopsLoadingWhileKeepingRequestOwnership() {
        val state = trackerState()
        val tracker = LocalRepositoryExpansionTracker(state)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))

        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, listOf(worktree("main"))))

        val repository = state.repositorySnapshot()
        assertEquals(RepositorySnapshot(isExpanded = true, isLoading = false, branches = listOf("main")), repository)
        assertTrue(tracker.complete(DEV_LAKE_ROOT, request, listOf(worktree("main"))))
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
    fun publishDiscoveredStopsLoadingWhileKeepingRequestOwnership() {
        val state = trackerState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))

        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, listOf(worktree("main"))))

        val repository = state.localRepositories.value.single()
        assertEquals(false, repository.isLoading)
        assertNotNull(repository.refreshRequest)
        assertTrue(tracker.complete(DEV_LAKE_ROOT, request, listOf(worktree("main"))))
        assertNull(state.localRepositories.value.single().refreshRequest)
    }

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

class LocalWorktreeStatusTrackerTest {
    @Test
    fun publishFillsStatusOnlyForTheRowMatchingPathAndBranch() {
        val state = trackerState()
        val refreshTracker = LocalRepositoryRefreshTracker(state)
        val statusTracker = LocalWorktreeStatusTracker(state)
        val request = assertNotNull(refreshTracker.start(DEV_LAKE_ROOT))
        assertTrue(
            refreshTracker.publishDiscovered(
                DEV_LAKE_ROOT,
                request,
                listOf(
                    worktree("main").copy(path = "$DEV_LAKE_ROOT-main"),
                    worktree("feature/login").copy(path = DEV_LAKE_SELECTED_WORKTREE),
                ),
            ),
        )

        assertTrue(
            statusTracker.publish(
                normalizedRepoRootPath = DEV_LAKE_ROOT,
                request = request,
                worktreePath = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/login",
                isDirty = true,
            ),
        )

        val repository = state.localRepositories.value.single()
        assertEquals(null, repository.worktrees.single { it.branch == "main" }.isDirty)
        assertEquals(true, repository.worktrees.single { it.branch == "feature/login" }.isDirty)

        // A different branch at the same path must not receive the status.
        assertTrue(
            statusTracker.publish(
                normalizedRepoRootPath = DEV_LAKE_ROOT,
                request = request,
                worktreePath = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/logout",
                isDirty = false,
            ),
        )
        val rowsAfterBranchMismatch = state.localRepositories.value.single().worktrees
        assertEquals(null, rowsAfterBranchMismatch.singleOrNull { it.branch == "feature/logout" })
        assertEquals(
            true,
            rowsAfterBranchMismatch.single { it.branch == "feature/login" }.isDirty,
        )
    }

    @Test
    fun publishIsDiscardedOnceANewerRequestOwnsTheRows() {
        val state = trackerState()
        val refreshTracker = LocalRepositoryRefreshTracker(state)
        val statusTracker = LocalWorktreeStatusTracker(state)
        val oldRequest = assertNotNull(refreshTracker.start(DEV_LAKE_ROOT))
        assertTrue(
            refreshTracker.publishDiscovered(DEV_LAKE_ROOT, oldRequest, listOf(worktree("feature/login"))),
        )

        val newRequest = assertNotNull(refreshTracker.start(DEV_LAKE_ROOT))
        assertTrue(
            refreshTracker.publishDiscovered(DEV_LAKE_ROOT, newRequest, listOf(worktree("feature/login"))),
        )

        assertFalse(
            statusTracker.publish(
                normalizedRepoRootPath = DEV_LAKE_ROOT,
                request = oldRequest,
                worktreePath = "$DEV_LAKE_ROOT/feature/login",
                branch = "feature/login",
                isDirty = true,
            ),
        )
        assertEquals(
            null,
            state.localRepositories.value.single().worktrees.single().isDirty,
        )
    }

    @Test
    fun publishSurvivesEnrichmentCompletionUntilANewerRequestStarts() {
        val state = trackerState()
        val refreshTracker = LocalRepositoryRefreshTracker(state)
        val statusTracker = LocalWorktreeStatusTracker(state)
        val request = assertNotNull(refreshTracker.start(DEV_LAKE_ROOT))
        assertTrue(
            refreshTracker.publishDiscovered(DEV_LAKE_ROOT, request, listOf(worktree("feature/login"))),
        )
        assertTrue(refreshTracker.complete(DEV_LAKE_ROOT, request, listOf(worktree("feature/login"))))

        assertTrue(
            statusTracker.publish(
                normalizedRepoRootPath = DEV_LAKE_ROOT,
                request = request,
                worktreePath = "$DEV_LAKE_ROOT/feature/login",
                branch = "feature/login",
                isDirty = true,
            ),
        )
        assertEquals(
            true,
            state.localRepositories.value.single().worktrees.single().isDirty,
        )
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
