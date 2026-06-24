package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
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
