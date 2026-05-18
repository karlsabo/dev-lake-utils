package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreePanelTest {

    @Test
    fun rootWorktreeMenuDoesNotExposeArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "main",
            path = "/repos/dev-lake-utils",
            isRoot = true,
        )

        assertEquals(
            listOf(WorktreeMenuAction.Open),
            visibleWorktreeMenuActions(worktree),
        )
    }

    @Test
    fun nonRootWorktreeMenuExposesArchive() {
        val worktree = LocalWorktreeUiState(
            branch = "feature/worktree-panel",
            path = "/repos/dev-lake-utils-feature-worktree-panel",
            isRoot = false,
        )

        assertEquals(
            listOf(WorktreeMenuAction.Open, WorktreeMenuAction.Archive),
            visibleWorktreeMenuActions(worktree),
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
