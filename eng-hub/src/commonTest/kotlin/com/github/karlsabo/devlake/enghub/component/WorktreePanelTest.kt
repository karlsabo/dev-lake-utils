package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreePanelTest {

    @Test
    fun rootWorktreeMenuExposesCreateWorktreeButNotArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            isRoot = true,
        )

        assertEquals(
            listOf(WorktreeMenuAction.Open, WorktreeMenuAction.CreateWorktree),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun nonRootWorktreeMenuExposesCreateWorktreeAndArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/worktree-panel",
            path = "/repos/dev-lake-utils-feature-worktree-panel",
            isRoot = false,
        )

        assertEquals(
            listOf(
                WorktreeMenuAction.Open,
                WorktreeMenuAction.CreateWorktree,
                WorktreeMenuAction.Archive,
            ),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun createWorktreeDialogUsesSelectedBaseAndEmptyTargetBranch() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
            isRoot = false,
        )

        assertEquals(
            PendingCreateWorktree(
                repoRootPath = "/repos/dev-lake-utils",
                baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr",
                baseBranch = "feature/base-pr",
                targetBranch = "",
            ),
            createWorktreeDialogState(
                repoRootPath = "/repos/dev-lake-utils",
                worktree = worktree,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsDisabledWhileSetupIsInProgress() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsDisabledWhileArchiveIsInProgress() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = true,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsDisabledForDetachedWorktree() {
        val worktree = LocalWorktreeUiState(
            branch = "(detached)",
            path = "/repos/dev-lake-utils-detached",
        )

        assertFalse(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun createWorktreeActionIsEnabledForIdleBranchWorktree() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/base-pr",
            path = "/repos/dev-lake-utils-feature-base-pr",
        )

        assertTrue(
            isWorktreeCreateEnabled(
                worktree = worktree,
                setupStatus = null,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun archiveActionIsDisabledWhileSetupIsInProgress() {
        assertFalse(
            isWorktreeArchiveEnabled(
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun archiveActionIsDisabledWhileArchiveIsInProgress() {
        assertFalse(isWorktreeArchiveEnabled(setupStatus = null, isArchiving = true))
    }

    @Test
    fun archiveActionIsEnabledWhenWorktreeIsIdle() {
        assertTrue(isWorktreeArchiveEnabled(setupStatus = null, isArchiving = false))
    }
}
