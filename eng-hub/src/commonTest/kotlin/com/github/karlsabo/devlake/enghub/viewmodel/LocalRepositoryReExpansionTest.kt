package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRepositoryReExpansionTest {
    @Test
    fun offlineReExpansionRetainsOriginActionsAndStackMetadata() {
        val known = listOf(
            row("main").copy(canUpdateFromOrigin = true),
            row("feature").copy(integrationTargetBranch = "main"),
            row("child").copy(parentBranch = "feature", integrationTargetBranch = "feature", needsRebase = true),
        )
        val state = stateWith(known)
        val tracker = LocalRepositoryExpansionTracker(state)
        tracker.collapse(DEV_LAKE_ROOT)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val discovered = known.map { row(it.branch) }

        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, discovered))
        val published = state.localRepositories.value.single().worktrees
        val expected = known.zip(discovered) { previous, current -> previous.copy(checkout = current.checkout) }
        assertEquals(expected, published)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchFailure = IllegalStateException("offline without cached origin"),
                unresolvedParentBranches = setOf("child"),
            ),
        )
        val enrichment = api.lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, discovered)
        assertTrue(tracker.complete(DEV_LAKE_ROOT, request, enrichment))

        assertEquals(published, state.localRepositories.value.single().worktrees)
        assertNull(state.localRepositories.value.single().operationRequest)
        published.zip(known).forEach { (current, previous) -> assertNotSame(previous.checkout, current.checkout) }
    }

    @Test
    fun reExpansionDoesNotTransferMetadataToReplacedCheckoutsOrRemovedParents() {
        val known = listOf(
            row("main").copy(canUpdateFromOrigin = true),
            row("child").copy(parentBranch = "main", integrationTargetBranch = "main", needsRebase = true),
            row("(detached)").copy(baseCommitHash = "old", integrationTargetBranch = "main"),
        )
        val state = stateWith(known)
        val tracker = LocalRepositoryExpansionTracker(state)
        tracker.collapse(DEV_LAKE_ROOT)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val discovered = listOf(
            row("replacement").copy(path = known.first().path),
            row("child"),
            row("(detached)").copy(baseCommitHash = "new"),
        )

        assertTrue(tracker.publishDiscovered(DEV_LAKE_ROOT, request, discovered))

        assertEquals(discovered, state.localRepositories.value.single().worktrees)
    }

    private fun row(branch: String) = LocalWorktreeUiState(branch = branch, path = "$DEV_LAKE_ROOT/$branch")

    private fun stateWith(worktrees: List<LocalWorktreeUiState>): EngHubViewModelState {
        val state = EngHubViewModelState(
            config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
            configWriter = RecordingEngHubConfigWriter(),
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = RecordingGitWorktreeApi()),
            notificationIgnoreStore = NoOpNotificationIgnoreStore(),
        )
        state.localRepositories.value = state.localRepositories.value.map {
            it.copy(isExpanded = true, worktrees = worktrees)
        }
        return state
    }
}
