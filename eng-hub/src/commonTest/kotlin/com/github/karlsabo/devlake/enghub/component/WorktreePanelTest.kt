package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
