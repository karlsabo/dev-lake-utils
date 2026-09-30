package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorktreeEnrichmentConcurrencyTest {
    @Test
    fun slowEnrichmentAppliesAcrossRepeatedUnchangedDiscoveryWithoutClearingNewestRequest() = runTest {
        val state = enrichmentState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val firstRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, firstRequest, listOf(checkout())))
        val firstRows = state.localRepositories.value.single().worktrees
        var newestRequest = firstRequest
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    // Discovery laps every lookup, not just the first one, with no timing dependency.
                    repeat(3) {
                        newestRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
                        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, newestRequest, listOf(checkout())))
                    }
                },
            ),
        )
        val scheduler = LocalWorktreeEnrichmentScheduler(backgroundScope, api)
        repeat(3) {
            val request = if (it == 0) firstRequest else newestRequest
            val rows = if (it == 0) firstRows else state.localRepositories.value.single().worktrees
            scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, request, rows) { result ->
                assertTrue(tracker.complete(DEV_LAKE_ROOT, request, result.getOrThrow()))
            }
            runCurrent()
            assertTrue(state.localRepositories.value.single().worktrees.single().canUpdateFromOrigin)
            assertSame(newestRequest, state.localRepositories.value.single().refreshRequest)
        }
    }

    @Test
    fun queuedFailureRetainsAuthoritativeClearAndResolvedLocalHierarchy() = runTest {
        val state = enrichmentState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val rows = listOf(
            checkout().copy(canUpdateFromOrigin = true, isDirty = true),
            checkout().copy(branch = "base", path = "/base", isDirty = false),
            checkout().copy(branch = "child", path = "/child", isDirty = true),
            checkout().copy(branch = "feature", path = "/feature", integrationTargetBranch = "main"),
        )
        state.localRepositories.value = state.localRepositories.value.map { it.copy(worktrees = rows) }
        val first = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, first, rows))
        val firstRows = state.localRepositories.value.single().worktrees
        val applied = mutableListOf<String>()
        lateinit var scheduler: LocalWorktreeEnrichmentScheduler
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to null),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("child" to "base")),
                branchNeedsRebaseByCall = mapOf(BranchNeedsRebaseCall(DEV_LAKE_ROOT, "base", "child") to true),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    if (applied.isNotEmpty()) error("offline without cached HEAD")
                    val queued = assertNotNull(tracker.start(DEV_LAKE_ROOT))
                    assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, queued, rows))
                    val queuedRows = state.localRepositories.value.single().worktrees
                    assertTrue(queuedRows.first().canUpdateFromOrigin)
                    scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, queued, queuedRows) { result ->
                        assertTrue(tracker.complete(DEV_LAKE_ROOT, queued, result.getOrThrow()))
                        applied += "failure"
                    }
                },
            ),
        )
        scheduler = LocalWorktreeEnrichmentScheduler(backgroundScope, api)
        scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, first, firstRows) { result ->
            assertTrue(tracker.complete(DEV_LAKE_ROOT, first, result.getOrThrow()))
            assertFalse(state.localRepositories.value.single().worktrees.first().canUpdateFromOrigin)
            applied += "clear"
        }
        runCurrent()

        assertEquals(listOf("clear", "failure"), applied)
        val current = state.localRepositories.value.single().worktrees
        assertFalse(current.first().canUpdateFromOrigin)
        assertEquals(null, current.single { it.branch == "feature" }.integrationTargetBranch)
        val child = current.single { it.branch == "child" }
        assertEquals("base", child.parentBranch)
        assertEquals("base", child.integrationTargetBranch)
        assertTrue(child.needsRebase)
        assertEquals(rows.map { it.isDirty }, current.map { it.isDirty })
    }

    @Test
    fun replacedCheckoutCannotReceiveOldEnrichmentEvenIfOriginalBranchReturns() {
        val state = enrichmentState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val oldRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, oldRequest, listOf(checkout())))
        val oldRows = state.localRepositories.value.single().worktrees
        for (branch in listOf("replacement", "main")) {
            val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))
            assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, listOf(checkout().copy(branch = branch))))
        }

        assertFalse(
            tracker.complete(
                DEV_LAKE_ROOT,
                oldRequest,
                LocalWorktreeEnrichment(oldRows.map { it.copy(canUpdateFromOrigin = true) }),
            ),
        )
        assertFalse(state.localRepositories.value.single().worktrees.single().canUpdateFromOrigin)
    }

    @Test
    fun lateEnrichmentOnlyUpdatesContinuouslyDiscoveredRowsAndDoesNotSurviveCollapse() {
        val state = enrichmentState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val original = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val feature = checkout().copy(branch = "feature", path = DEV_LAKE_SELECTED_WORKTREE)
        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, original, listOf(checkout(), feature)))
        val oldRows = state.localRepositories.value.single().worktrees.map {
            it.copy(canUpdateFromOrigin = true)
        }
        val newest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        assertTrue(
            tracker.publishDiscovered(
                DEV_LAKE_ROOT,
                newest,
                listOf(checkout().copy(isDirty = true), feature.copy(branch = "replacement")),
            ),
        )

        assertTrue(tracker.complete(DEV_LAKE_ROOT, original, LocalWorktreeEnrichment(oldRows)))
        val current = state.localRepositories.value.single()
        assertTrue(current.worktrees.first().canUpdateFromOrigin)
        assertEquals(true, current.worktrees.first().isDirty)
        assertFalse(current.worktrees.last().canUpdateFromOrigin)
        assertSame(newest, current.refreshRequest)

        LocalRepositoryExpansionTracker(state).collapse(DEV_LAKE_ROOT)
        assertFalse(tracker.complete(DEV_LAKE_ROOT, original, LocalWorktreeEnrichment(oldRows)))
    }

    @Test
    fun olderProducerCannotEvictNewestQueuedRequestOrRequeueAfterItWasConsumed() = runTest {
        val running = LocalRepositoryWorktreeRequest()
        val older = LocalRepositoryWorktreeRequest()
        val newest = LocalRepositoryWorktreeRequest()
        val applied = mutableListOf<String>()
        lateinit var scheduler: LocalWorktreeEnrichmentScheduler
        val api = RecordingGitWorktreeApi(
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    if (applied.isEmpty()) {
                        // The running lookup holds the worker while producers arrive out of order.
                        scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, newest, listOf(checkout())) {
                            applied += "newest"
                        }
                        scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, older, listOf(checkout())) {
                            applied += "older"
                        }
                    }
                },
            ),
        )
        scheduler = LocalWorktreeEnrichmentScheduler(backgroundScope, api)
        scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, running, listOf(checkout())) { applied += "running" }
        runCurrent()
        assertEquals(listOf("running", "newest"), applied)

        scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, older, listOf(checkout())) { applied += "late older" }
        runCurrent()
        assertEquals(listOf("running", "newest"), applied)
        assertEquals(2, api.inferOriginDefaultBranchRepoPaths.size)
    }
}

private fun checkout() = LocalWorktreeUiState(branch = "main", path = DEV_LAKE_ROOT)

private fun enrichmentState(): EngHubViewModelState = EngHubViewModelState(
    config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
    configWriter = RecordingEngHubConfigWriter(),
    worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = RecordingGitWorktreeApi()),
    notificationIgnoreStore = NoOpNotificationIgnoreStore(),
)
