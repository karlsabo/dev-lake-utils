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
    fun queuedArchivePathIsOmittedFromVisibleRows() {
        val root = LocalWorktreeUiState(branch = "main", path = "/repos/widgets", isRoot = true)
        val queued = LocalWorktreeUiState(branch = "feature/login", path = "/repos/widgets-feature-login")

        val rows = visibleWorktreeRows(
            worktrees = listOf(root, queued),
            hiddenPaths = setOf("/repos/widgets-feature-login/"),
        )

        assertEquals(listOf("main"), rows.map { it.worktree.branch })
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
    fun manualIntegrationActionsUseIntegrationTargetWithoutAnInferredParent() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/audit",
            path = "/repos/legacy-api-feature-audit",
            integrationTargetBranch = "master",
        )

        val actions = visibleWorktreeMenuActions(worktree)

        assertTrue(WorktreeMenuAction.RebaseOntoParent in actions)
        assertTrue(WorktreeMenuAction.MergeOntoParent in actions)
    }

    @Test
    fun manualIntegrationActionsAreHiddenWithoutANonSelfIntegrationTarget() {
        val withoutTarget = LocalWorktreeUiState(
            branch = "feature/audit",
            path = "/repos/legacy-api-feature-audit",
        )
        val selfTarget = withoutTarget.copy(branch = "master", integrationTargetBranch = "master")

        listOf(withoutTarget, selfTarget).forEach { worktree ->
            val actions = visibleWorktreeMenuActions(worktree)
            assertFalse(WorktreeMenuAction.RebaseOntoParent in actions)
            assertFalse(WorktreeMenuAction.MergeOntoParent in actions)
        }
    }

    @Test
    fun mergeActionIsExcludedWhileAnotherIntegrationIsInProgress() {
        assertFalse(isWorktreeStatusDependentActionEnabled(statusDependentActionRowState(isRebasing = true)))
        assertFalse(isWorktreeStatusDependentActionEnabled(statusDependentActionRowState(isMerging = true)))
        assertTrue(isWorktreeStatusDependentActionEnabled(statusDependentActionRowState()))
    }

    @Test
    fun updateProgressDisablesEveryConflictingWorktreeAction() {
        val state = statusDependentActionRowState(isUpdating = true)

        assertFalse(isWorktreeOpenEnabled(state))
        assertFalse(isWorktreeCreateEnabled(state))
        assertFalse(isWorktreeStatusDependentActionEnabled(state))
    }

    @Test
    fun unknownDirtyStatusDisablesStatusDependentActionsButNotOpenOrCreate() {
        val state = statusDependentActionRowState(isDirty = null)

        assertTrue(isWorktreeOpenEnabled(state))
        assertTrue(isWorktreeCreateEnabled(state))
        assertFalse(isWorktreeStatusDependentActionEnabled(state))
    }

    private fun statusDependentActionRowState(
        isDirty: Boolean? = false,
        isUpdating: Boolean = false,
        isRebasing: Boolean = false,
        isMerging: Boolean = false,
    ): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/login",
            path = "/repos/dev-lake-utils-feature-login",
            isDirty = isDirty,
        ),
        setupStatus = null,
        isArchiving = false,
        isUpdating = isUpdating,
        isRebasing = isRebasing,
        isMerging = isMerging,
    )

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
