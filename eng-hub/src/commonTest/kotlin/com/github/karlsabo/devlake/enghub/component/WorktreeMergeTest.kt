package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeMergeTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mergingWorktreeRowRendersProgressLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = mergeRowState(isMerging = true),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        onNodeWithText("Merging...").assertIsDisplayed()
    }

    @Test
    fun mergeActionIsDisabledWhileSetupIsInProgress() {
        assertFalse(
            isWorktreeMergeEnabled(
                setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                isArchiving = false,
            ),
        )
    }

    @Test
    fun mergeActionIsDisabledWhileRebaseIsInProgress() {
        assertFalse(
            isWorktreeMergeEnabled(
                setupStatus = null,
                isArchiving = false,
                isRebasing = true,
            ),
        )
    }

    @Test
    fun mergeActionIsDisabledWhileMergeIsInProgress() {
        assertFalse(
            isWorktreeMergeEnabled(
                setupStatus = null,
                isArchiving = false,
                isMerging = true,
            ),
        )
    }

    @Test
    fun mergeActionIsEnabledWhenWorktreeIsIdle() {
        assertTrue(isWorktreeMergeEnabled(setupStatus = null, isArchiving = false, isMerging = false))
    }

    private fun mergeRowState(isMerging: Boolean): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            parentBranch = "feature/base-pr",
        ),
        setupStatus = null,
        isArchiving = false,
        isMerging = isMerging,
    )
}
