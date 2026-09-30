package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalWorktreeLookupOutcomesTest {
    @Test
    fun authoritativeNoHeadClearsOriginActionsButKeepsVerifiedLocalParent() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to null),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("child" to "base")),
                branchNeedsRebaseByCall = mapOf(BranchNeedsRebaseCall(DEV_LAKE_ROOT, "base", "child") to true),
            ),
        )

        val rows = api.enrichLocalWorktreeUiStates(DEV_LAKE_ROOT, knownRows())

        assertEquals(false, rows.first().canUpdateFromOrigin)
        assertEquals(null, rows.single { it.branch == "feature" }.integrationTargetBranch)
        val child = rows.single { it.branch == "child" }
        assertEquals("base", child.parentBranch)
        assertEquals("base", child.integrationTargetBranch)
        assertEquals(true, child.needsRebase)
    }

    @Test
    fun offlineLookupDoesNotRestoreLocallyRuledOutHierarchy() {
        val api = offlineApi()

        val rows = api.enrichLocalWorktreeUiStates(DEV_LAKE_ROOT, knownRows())

        assertEquals(true, rows.first().canUpdateFromOrigin)
        assertEquals("main", rows.single { it.branch == "feature" }.integrationTargetBranch)
        val child = rows.single { it.branch == "child" }
        assertEquals(null, child.parentBranch)
        assertEquals(null, child.integrationTargetBranch)
        assertEquals(false, child.needsRebase)
    }

    @Test
    fun offlineLookupRetainsHierarchyOnlyForUnresolvedLocalChecks() {
        val api = offlineApi(setOf("child"))
        val previous = knownRows()

        assertEquals(previous, api.enrichLocalWorktreeUiStates(DEV_LAKE_ROOT, previous))
    }

    @Test
    fun lateLookupMergeKeepsHydratedStatusAndRejectsDifferentPathOrBranch() {
        val enriched = offlineApi().enrichLocalWorktreeUiStates(DEV_LAKE_ROOT, knownRows())
        val current = listOf(
            LocalWorktreeUiState(branch = "feature", path = "/repo-feature/", isDirty = true),
            LocalWorktreeUiState(branch = "replacement", path = "/repo-child", isDirty = false),
            LocalWorktreeUiState(branch = "feature", path = "/new-feature", isDirty = true),
        )

        val merged = current.withEnrichmentFrom(enriched)

        assertEquals(current.first().copy(integrationTargetBranch = "main"), merged.first())
        assertEquals(current.drop(1), merged.drop(1))
    }

    @Test
    fun unresolvedLookupsRetainCurrentHierarchyRatherThanQueuedHierarchy() {
        val enrichment = offlineApi(setOf("child")).lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, knownRows())
        val current = knownRows().map {
            when (it.branch) {
                "main" -> it.copy(canUpdateFromOrigin = false)
                "child" -> it.copy(parentBranch = "main", integrationTargetBranch = "main", needsRebase = false)
                "feature" -> it.copy(integrationTargetBranch = null)
                else -> it
            }.copy(isDirty = true)
        }

        assertEquals(current, enrichment.mergeInto(current))
    }

    private fun offlineApi(
        unresolvedParents: Set<String> = emptySet(),
    ): RecordingGitWorktreeApi = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            originDefaultBranchFailure = IllegalStateException("offline without cached HEAD"),
            unresolvedParentBranches = unresolvedParents,
        ),
    )

    private fun knownRows(): List<LocalWorktreeUiState> = listOf(
        LocalWorktreeUiState(branch = "main", path = "/repo", canUpdateFromOrigin = true),
        LocalWorktreeUiState(branch = "base", path = "/repo-base"),
        LocalWorktreeUiState(
            branch = "child",
            path = "/repo-child",
            parentBranch = "base",
            needsRebase = true,
            integrationTargetBranch = "base",
        ),
        LocalWorktreeUiState(branch = "feature", path = "/repo-feature", integrationTargetBranch = "main"),
    )
}
