package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.Worktree
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalWorktreeStateMappersTest {
    @Test
    fun enrichmentUsesCurrentServerHeadWhenLocalOriginHeadIsStale() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                defaultBranchRefsByRepoPath = mapOf(DEV_LAKE_ROOT to "origin/main"),
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "trunk"),
            ),
        )
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = "$DEV_LAKE_ROOT-trunk", branch = "trunk", commitHash = "def456"),
            Worktree(path = "$DEV_LAKE_ROOT-feature", branch = "feature", commitHash = "ghi789"),
        )

        val enriched = api.toLocalWorktreeUiStates(DEV_LAKE_ROOT, worktrees)

        assertEquals(
            mapOf("main" to false, "trunk" to true, "feature" to false),
            enriched.associate { it.branch to it.canUpdateFromOrigin },
        )
    }

    @Test
    fun enrichmentUsesOriginDefaultAsIntegrationTargetWhenVisibleParentIsNotInferred() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
        )
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = "$DEV_LAKE_ROOT-feature", branch = "feature/login", commitHash = "def456"),
        )

        val enriched = api.toLocalWorktreeUiStates(DEV_LAKE_ROOT, worktrees)
        val feature = enriched.single { it.branch == "feature/login" }

        assertEquals(null, feature.parentBranch)
        assertEquals("main", feature.integrationTargetBranch)
        assertEquals(null, enriched.single { it.branch == "main" }.integrationTargetBranch)
    }

    @Test
    fun enrichmentPrefersVisibleInferredParentOverOriginDefaultIntegrationTarget() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked" to "feature/base"),
                ),
            ),
        )
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = "$DEV_LAKE_ROOT-base", branch = "feature/base", commitHash = "def456"),
            Worktree(path = "$DEV_LAKE_ROOT-stacked", branch = "feature/stacked", commitHash = "ghi789"),
        )

        val stacked = api.toLocalWorktreeUiStates(DEV_LAKE_ROOT, worktrees)
            .single { it.branch == "feature/stacked" }

        assertEquals("feature/base", stacked.parentBranch)
        assertEquals("feature/base", stacked.integrationTargetBranch)
    }

    @Test
    fun enrichmentRemovesInferredParentActionsFromOriginDefaultWorktree() {
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("main" to "develop")),
                branchNeedsRebaseByCall = mapOf(
                    BranchNeedsRebaseCall(DEV_LAKE_ROOT, "develop", "main") to true,
                ),
            ),
        )
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = "$DEV_LAKE_ROOT-develop", branch = "develop", commitHash = "def456"),
        )

        val originDefault = api.toLocalWorktreeUiStates(DEV_LAKE_ROOT, worktrees).first()

        assertEquals(true, originDefault.canUpdateFromOrigin)
        assertEquals(null, originDefault.parentBranch)
        assertEquals(null, originDefault.integrationTargetBranch)
        assertEquals(false, originDefault.needsRebase)
    }

    @Test
    fun discoveryPreservesOriginUpdateEligibilityFromEnrichment() {
        val path = "/repo"
        val discovered = listOf(LocalWorktreeUiState(branch = "main", path = path))
        val enriched = listOf(
            LocalWorktreeUiState(branch = "main", path = path, canUpdateFromOrigin = true),
        )

        assertEquals(true, discovered.withEnrichmentFrom(enriched).single().canUpdateFromOrigin)
    }

    @Test
    fun discoveryPreservesIntegrationTargetFromEnrichment() {
        val path = "/repo-feature"
        val discovered = listOf(LocalWorktreeUiState(branch = "feature/login", path = path))
        val enriched = listOf(
            LocalWorktreeUiState(
                branch = "feature/login",
                path = path,
                integrationTargetBranch = "main",
            ),
        )

        assertEquals("main", discovered.withEnrichmentFrom(enriched).single().integrationTargetBranch)
    }

    @Test
    fun discoveryDropsEnrichmentWhenParentIsNoLongerVisible() {
        val childPath = "/repo-stacked"
        val discoveredWorktrees = listOf(
            LocalWorktreeUiState(branch = "feature/stacked-pr", path = childPath),
        )
        val previousWorktrees = listOf(
            LocalWorktreeUiState(branch = "feature/base-pr", path = "/repo-base"),
            LocalWorktreeUiState(
                branch = "feature/stacked-pr",
                path = childPath,
                parentBranch = "feature/base-pr",
                needsRebase = true,
            ),
        )

        val child = discoveredWorktrees.withEnrichmentFrom(previousWorktrees).single()

        assertEquals(null, child.parentBranch)
        assertEquals(false, child.needsRebase)
    }
}
