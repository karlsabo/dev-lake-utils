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
            isWorktreeStatusDependentActionEnabled(
                mergeRowState().copy(setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE),
            ),
        )
    }

    @Test
    fun mergeActionIsDisabledWhileRebaseIsInProgress() {
        assertFalse(
            isWorktreeStatusDependentActionEnabled(
                mergeRowState(isRebasing = true),
            ),
        )
    }

    @Test
    fun mergeActionIsDisabledWhileMergeIsInProgress() {
        assertFalse(
            isWorktreeStatusDependentActionEnabled(
                mergeRowState(isMerging = true),
            ),
        )
    }

    @Test
    fun mergeActionIsEnabledWhenWorktreeIsIdleAndDirtyStatusIsKnown() {
        assertTrue(
            isWorktreeStatusDependentActionEnabled(
                mergeRowState(),
            ),
        )
    }

    private fun mergeRowState(
        isRebasing: Boolean = false,
        isMerging: Boolean = false,
    ): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/stacked-pr",
            path = "/repos/dev-lake-utils-feature-stacked-pr",
            isDirty = false,
            parentBranch = "feature/base-pr",
        ),
        setupStatus = null,
        isArchiving = false,
        isRebasing = isRebasing,
        isMerging = isMerging,
    )
}
