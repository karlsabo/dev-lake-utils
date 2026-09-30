package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorktreeStatusSchedulerTest {
    @Test
    fun blockedStatusCoalescesUnchangedRefreshesAndValidSlowCompletionHydrates() = runTest {
        val state = statusState()
        val release = CompletableDeferred<Boolean>()
        val nextRelease = CompletableDeferred<Boolean>()
        var calls = 0
        val scheduler = LocalWorktreeStatusScheduler(backgroundScope, state) {
            calls++
            if (calls == 1) release.await() else nextRelease.await()
        }
        discoverAndSchedule(state, scheduler)
        runCurrent()
        repeat(100) {
            discoverAndSchedule(state, scheduler)
            runCurrent()
        }
        assertEquals(1, calls)
        assertEquals(null, state.localRepositories.value.single().worktrees.single().isDirty)
        release.complete(true)
        runCurrent()
        assertEquals(true, state.localRepositories.value.single().worktrees.single().isDirty)
        assertEquals(2, calls, "only the latest pending refresh runs after the valid slow completion")
        nextRelease.complete(false)
        runCurrent()
        assertEquals(false, state.localRepositories.value.single().worktrees.single().isDirty)

        discoverAndSchedule(state, scheduler)
        runCurrent()
        assertEquals(3, calls, "an idle slot must permit a fresh check")
    }

    @Test
    fun changedBranchRejectsOldStatusAndRunsOnlyLatestPendingCheckout() = runTest {
        val state = statusState()
        val first = CompletableDeferred<Boolean>()
        val latest = CompletableDeferred<Boolean>()
        var calls = 0
        val scheduler = LocalWorktreeStatusScheduler(backgroundScope, state) {
            calls++
            if (calls == 1) first.await() else latest.await()
        }
        discoverAndSchedule(state, scheduler, "feature/login")
        runCurrent()
        repeat(100) { discoverAndSchedule(state, scheduler, "replacement-$it") }
        discoverAndSchedule(state, scheduler, "feature/logout")
        runCurrent()
        assertEquals(1, calls)
        first.complete(true)
        runCurrent()
        val row = state.localRepositories.value.single().worktrees.single()
        assertEquals("feature/logout", row.branch)
        assertEquals(null, row.isDirty)
        assertEquals(2, calls)
        latest.complete(false)
        runCurrent()
        assertEquals(false, state.localRepositories.value.single().worktrees.single().isDirty)
    }

    @Test
    fun returnedOriginalBranchAndCollapseRejectInFlightStatus() = runTest {
        val state = statusState()
        val release = CompletableDeferred<Boolean>()
        val scheduler = LocalWorktreeStatusScheduler(backgroundScope, state) { release.await() }
        discoverAndSchedule(state, scheduler, "feature/login")
        runCurrent()
        val oldRow = state.localRepositories.value.single().worktrees.single()
        discoverAndSchedule(state, scheduler, "feature/logout")
        discoverAndSchedule(state, scheduler, "feature/login")
        assertFalse(oldRow.checkout === state.localRepositories.value.single().worktrees.single().checkout)
        LocalRepositoryExpansionTracker(state).collapse(DEV_LAKE_ROOT)
        release.complete(true)
        runCurrent()
        assertEquals(null, state.localRepositories.value.single().worktrees.single().isDirty)
    }

    @Test
    fun disposalRejectsNonCooperativeCompletionAndDoesNotStartQueuedWork() = runTest {
        val state = statusState()
        val release = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        var calls = 0
        val scheduler = LocalWorktreeStatusScheduler(scope, state) {
            calls++
            withContext(NonCancellable) { release.await() }
        }
        discoverAndSchedule(state, scheduler)
        runCurrent()
        discoverAndSchedule(state, scheduler, "replacement")
        scope.cancel()
        discoverAndSchedule(state, scheduler, "latest")
        release.complete(true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(null, state.localRepositories.value.single().worktrees.single().isDirty)
    }

    @Test
    fun addingBDoesNotInvalidateABlockedStatusAndBChecksIndependently() = runTest {
        val state = statusState()
        val releaseA = CompletableDeferred<Boolean>()
        val scheduler = LocalWorktreeStatusScheduler(backgroundScope, state) { path ->
            if (path == DEV_LAKE_ROOT) releaseA.await() else false
        }
        discoverAndSchedule(state, scheduler)
        runCurrent()
        preserveAWhileAddingB(state)
        val tracker = LocalRepositoryRefreshTracker(state)
        val requestB = assertNotNull(tracker.start(DOCS_ROOT))
        assertTrue(tracker.publishDiscovered(DOCS_ROOT, requestB, listOf(statusRow().copy(path = DOCS_ROOT))))
        scheduler.schedule(DOCS_ROOT, requestB, state.localRepositories.value.last().worktrees)
        runCurrent()
        assertEquals(false, state.localRepositories.value.last().worktrees.single().isDirty)
        assertEquals(null, state.localRepositories.value.first().worktrees.single().isDirty)
        releaseA.complete(true)
        runCurrent()
        assertEquals(true, state.localRepositories.value.first().worktrees.single().isDirty)
    }

    @Test
    fun addingBPreservesABlockedDiscoveryAndStatusOwnership() {
        val state = statusState()
        val expansion = LocalRepositoryExpansionTracker(state)
        expansion.collapse(DEV_LAKE_ROOT)
        val operation = assertNotNull(expansion.start(DEV_LAKE_ROOT))
        preserveAWhileAddingB(state)
        assertTrue(expansion.publishDiscovered(DEV_LAKE_ROOT, operation, listOf(statusRow())))
        val row = state.localRepositories.value.first().worktrees.single()
        preserveAWhileAddingB(state)
        assertTrue(LocalWorktreeStatusTracker(state).publish(DEV_LAKE_ROOT, operation, row.path, row.branch, true))
        assertEquals(true, state.localRepositories.value.first().worktrees.single().isDirty)

        val refresh = LocalRepositoryRefreshTracker(state)
        val request = assertNotNull(refresh.start(DEV_LAKE_ROOT))
        preserveAWhileAddingB(state)
        assertTrue(refresh.publishDiscovered(DEV_LAKE_ROOT, request, listOf(statusRow())))
        preserveAWhileAddingB(state)
        assertTrue(refresh.complete(DEV_LAKE_ROOT, request, null))
    }
}

private fun preserveAWhileAddingB(state: EngHubViewModelState) {
    val before = state.localRepositories.value.first()
    state.localRepositories.value = listOf(
        LocalRepositoryUiState(name = "A", path = DEV_LAKE_ROOT),
        LocalRepositoryUiState(name = "B", path = DOCS_ROOT),
    ).withPreservedWorktrees(state.localRepositories.value, DOCS_ROOT, emptyList(), true)
    assertSame(before, state.localRepositories.value.first())
}

private fun discoverAndSchedule(
    state: EngHubViewModelState,
    scheduler: LocalWorktreeStatusScheduler,
    branch: String = "main",
) {
    val tracker = LocalRepositoryRefreshTracker(state)
    val request: LocalRepositoryWorktreeRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
    assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, listOf(statusRow().copy(branch = branch))))
    scheduler.schedule(DEV_LAKE_ROOT, request, state.localRepositories.value.single().worktrees)
}

private fun statusRow() = LocalWorktreeUiState(branch = "main", path = DEV_LAKE_ROOT)

private fun statusState() = EngHubViewModelState(
    config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
    configWriter = RecordingEngHubConfigWriter(),
    worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = RecordingGitWorktreeApi()),
    notificationIgnoreStore = NoOpNotificationIgnoreStore(),
)
