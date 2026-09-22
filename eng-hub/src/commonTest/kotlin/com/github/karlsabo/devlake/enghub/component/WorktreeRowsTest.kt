package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeRowsTest {

    @Test
    fun visibleWorktreeRowsNestOneChildLevelUnderParent() {
        val base = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )
        val stacked = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )

        val rows = visibleWorktreeRows(listOf(stacked, base))

        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 1), rows.map { it.nestingDepth })
    }

    @Test
    fun visibleWorktreeRowsRenderThreeBranchChain() {
        val branchA = LocalWorktreeUiState(
            branch = "branch-a",
            path = "/repos/dev-lake-utils-branch-a",
        )
        val branchB = LocalWorktreeUiState(
            branch = "branch-b",
            path = "/repos/dev-lake-utils-branch-b",
            parentBranch = "branch-a",
        )
        val branchC = LocalWorktreeUiState(
            branch = "branch-c",
            path = "/repos/dev-lake-utils-branch-c",
            parentBranch = "branch-b",
        )

        val rows = visibleWorktreeRows(listOf(branchC, branchB, branchA))

        assertEquals(listOf("branch-a", "branch-b", "branch-c"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 1, 2), rows.map { it.nestingDepth })
    }

    @Test
    fun worktreeRowIndentMakesGrandchildDepthVisible() {
        assertTrue(worktreeRowIndentDp(2) > worktreeRowIndentDp(1))
    }

    @Test
    fun visibleWorktreeRowsFallBackToFlatListWhenParentBranchesCycle() {
        val branchA = LocalWorktreeUiState(
            branch = "branch-a",
            path = "/repos/dev-lake-utils-branch-a",
            parentBranch = "branch-b",
        )
        val branchB = LocalWorktreeUiState(
            branch = "branch-b",
            path = "/repos/dev-lake-utils-branch-b",
            parentBranch = "branch-a",
        )

        val rows = visibleWorktreeRows(listOf(branchA, branchB))

        assertEquals(listOf("branch-a", "branch-b"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 0), rows.map { it.nestingDepth })
    }

    @Test
    fun worktreeMenuExposesMergeOntoParentAlongsideRebaseWhenParentIsKnown() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )

        assertEquals(
            listOf(
                WorktreeMenuAction.Open,
                WorktreeMenuAction.CreateWorktree,
                WorktreeMenuAction.RebaseOntoParent,
                WorktreeMenuAction.MergeOntoParent,
                WorktreeMenuAction.Archive,
            ),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun mergeActionIsExcludedWhileAnotherIntegrationIsInProgress() {
        assertFalse(isWorktreeMergeEnabled(setupStatus = null, isArchiving = false, isRebasing = true))
        assertFalse(isWorktreeMergeEnabled(setupStatus = null, isArchiving = false, isMerging = true))
        assertTrue(isWorktreeMergeEnabled(setupStatus = null, isArchiving = false))
    }

    @Test
    fun rebaseActionIsExcludedWhileMergeIsInProgress() {
        assertFalse(isWorktreeRebaseEnabled(setupStatus = null, isArchiving = false, isMerging = true))
    }

    @Test
    fun updateProgressDisablesEveryConflictingWorktreeAction() {
        val worktree = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            canUpdateFromOrigin = true,
        )

        assertFalse(isWorktreeOpenEnabled(null, isArchiving = false, isUpdating = true))
        assertFalse(isWorktreeCreateEnabled(worktree, null, isArchiving = false, isUpdating = true))
        assertFalse(isWorktreeArchiveEnabled(null, isArchiving = false, isUpdating = true))
        assertFalse(isWorktreeRebaseEnabled(null, isArchiving = false, isUpdating = true))
        assertFalse(isWorktreeMergeEnabled(null, isArchiving = false, isUpdating = true))
    }

    @Test
    fun visibleWorktreeRowsFallBackToFlatListWhenParentIsMissing() {
        val stacked = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        )
        val main = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            isRoot = true,
        )

        val rows = visibleWorktreeRows(listOf(stacked, main))

        assertEquals(listOf("feature/stacked-pr", "main"), rows.map { it.worktree.branch })
        assertEquals(listOf(0, 0), rows.map { it.nestingDepth })
    }
}
