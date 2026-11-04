package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeStatusIndicatorTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun knownDirtyStatusRendersDirtyAndCleanIndicators() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(state = worktreeRowState(isDirty = true))
            }
        }

        onNodeWithContentDescription("Dirty worktree").assertIsDisplayed()
        onAllNodesWithContentDescription("Checking status").assertCountEquals(0)
        onAllNodesWithText("Checking status…").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun unknownDirtyStatusShowsCheckingIndicatorAndDisablesStatusDependentActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(state = worktreeRowState(isDirty = null))
            }
        }

        onNodeWithContentDescription("Checking status").assertIsDisplayed()
        onAllNodesWithText("Checking status…").assertCountEquals(0)
        onNodeWithContentDescription("Update feature/login from base main").assertIsNotEnabled()
        onNodeWithContentDescription("Archive worktree feature/login").assertIsNotEnabled()
        onNodeWithContentDescription("Open worktree feature/login").assertIsEnabled()

        onNodeWithTag("worktree-row-feature/login").performMouseInput { rightClick() }
        onNodeWithText("Open").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Create worktree").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Rebase onto main").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Merge main into worktree").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Archive").assertIsDisplayed().assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun expandedRepositoryKeepsBranchLabelStationaryAcrossDirtyStatusChecks() = runComposeUiTest {
        val isDirty = mutableStateOf<Boolean?>(null)
        setContent {
            MaterialTheme {
                ExpandedRepositoryRow(isDirty = isDirty.value)
            }
        }

        // Initial display checks status, then clean, then dirty, then back to unknown on automatic refresh.
        val states = listOf<Pair<Boolean?, String>>(
            null to "Checking status",
            false to "Clean worktree",
            true to "Dirty worktree",
            null to "Checking status",
        )
        val geometries = states.map { (dirtyStatus, description) ->
            isDirty.value = dirtyStatus
            waitForIdle()

            onNodeWithContentDescription(description).assertIsDisplayed()
            measureStatusRow()
        }

        geometries.drop(1).forEachIndexed { index, geometry ->
            assertStatusRowStationary(geometries.first(), geometry, states[index + 1].second)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.measureStatusRow(): StatusRowGeometry = StatusRowGeometry(
        statusSlot = onNodeWithTag(WORKTREE_STATUS_SLOT_TAG).fetchSemanticsNode().boundsInRoot,
        branchLabel = onNodeWithText("feature/login").fetchSemanticsNode().boundsInRoot,
    )

    private fun assertStatusRowStationary(
        reference: StatusRowGeometry,
        current: StatusRowGeometry,
        transition: String,
    ) {
        assertEquals(
            reference.statusSlot.left,
            current.statusSlot.left,
            absoluteTolerance = 1f,
            message = "status slot moved at $transition",
        )
        assertEquals(
            reference.statusSlot.width,
            current.statusSlot.width,
            absoluteTolerance = 1f,
            message = "status slot resized at $transition",
        )
        assertEquals(
            reference.branchLabel.left,
            current.branchLabel.left,
            absoluteTolerance = 1f,
            message = "branch label moved at $transition",
        )
    }

    @Composable
    private fun ExpandedRepositoryRow(isDirty: Boolean?) {
        LocalRepositoryRow(
            state = WorktreeRowsState(
                repository = LocalRepositoryUiState(
                    name = "dev-lake-utils",
                    path = "/repos/dev-lake-utils",
                    isExpanded = true,
                    worktrees = listOf(
                        LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                            isDirty = isDirty,
                        ),
                    ),
                ),
                setupStatuses = emptyMap(),
                archivingWorktreePaths = emptySet(),
            ),
            panelActions = emptyPanelActions(),
            onCreateRequest = {},
        )
    }
}

/** Horizontal geometry of the status slot and branch label in a worktree row. */
private data class StatusRowGeometry(
    val statusSlot: Rect,
    val branchLabel: Rect,
)
