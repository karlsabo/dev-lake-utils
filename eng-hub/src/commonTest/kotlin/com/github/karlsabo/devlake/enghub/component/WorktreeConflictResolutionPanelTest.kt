package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.viewmodel.WorktreeIntegrationOperation
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeConflictResolutionPanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mergeConflictLeaveAsIsButtonCallsPanelBoundaryWithOriginalRequest() = runComposeUiTest {
        val requests = mutableListOf<PendingWorktreeConflictResolution>()
        val request = conflictRequest(WorktreeIntegrationOperation.Merge)

        setContent {
            MaterialTheme {
                WorktreePanel(
                    state = WorktreePanelState(
                        localRepositories = emptyList(),
                        forceArchiveRequest = null,
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        worktreeConflictResolutionRequest = request,
                    ),
                    actions = emptyPanelActions().copy(
                        onLeaveWorktreeConflictAsIs = { requests += it },
                    ),
                )
            }
        }

        onNodeWithText("Leave as-is").performClick()

        assertEquals(listOf(request), requests)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rebaseConflictLeaveAsIsButtonCallsPanelBoundaryWithOriginalRequest() = runComposeUiTest {
        val requests = mutableListOf<PendingWorktreeConflictResolution>()
        val request = conflictRequest(WorktreeIntegrationOperation.Rebase)

        setContent {
            MaterialTheme {
                WorktreePanel(
                    state = WorktreePanelState(
                        localRepositories = emptyList(),
                        forceArchiveRequest = null,
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        worktreeConflictResolutionRequest = request,
                    ),
                    actions = emptyPanelActions().copy(
                        onLeaveWorktreeConflictAsIs = { requests += it },
                    ),
                )
            }
        }

        onNodeWithText("Leave as-is").performClick()

        assertEquals(listOf(request), requests)
    }

    private fun conflictRequest(
        operation: WorktreeIntegrationOperation,
    ): PendingWorktreeConflictResolution = PendingWorktreeConflictResolution(
        operation = operation,
        repoRootPath = "/repos/dev-lake-utils",
        worktreePath = "/repos/dev-lake-utils-feature-stacked-pr",
        parentBranch = "feature/base-pr",
    )
}
