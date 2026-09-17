package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.viewmodel.WorktreeIntegrationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeConflictDialogTest {

    @Test
    fun abortingRebaseConflictDialogCallsBoundaryWithOriginalRequest() {
        val requests = mutableListOf<PendingWorktreeConflictResolution>()
        val request = conflictRequest(WorktreeIntegrationOperation.Rebase)

        abortWorktreeConflictDialog(request) { requests += it }

        assertEquals(listOf(request), requests)
    }

    @Test
    fun abortingMergeConflictDialogCallsBoundaryWithOriginalRequest() {
        val requests = mutableListOf<PendingWorktreeConflictResolution>()
        val request = conflictRequest(WorktreeIntegrationOperation.Merge)

        abortWorktreeConflictDialog(request) { requests += it }

        assertEquals(listOf(request), requests)
    }

    @Test
    fun leavingRebaseConflictAsIsDialogCallsBoundaryWithOriginalRequest() {
        val requests = mutableListOf<PendingWorktreeConflictResolution>()
        val request = conflictRequest(WorktreeIntegrationOperation.Rebase)

        leaveRebaseConflictAsIsDialog(request) { requests += it }

        assertEquals(listOf(request), requests)
    }

    @Test
    fun rebaseConflictContentOffersAbortAndLeaveWithRebaseWording() {
        val content = worktreeConflictDialogContent(conflictRequest(WorktreeIntegrationOperation.Rebase))

        assertEquals("Rebase Conflict", content.windowTitle)
        assertEquals("Rebase conflict", content.heading)
        assertTrue(content.summary.contains("Rebase onto"))
        assertTrue(content.guidance.contains("Abort the rebase"))
        assertTrue(content.canLeaveAsIs)
    }

    @Test
    fun mergeConflictContentUsesMergeWordingWithoutLeaveAsIs() {
        val content = worktreeConflictDialogContent(conflictRequest(WorktreeIntegrationOperation.Merge))

        assertEquals("Merge Conflict", content.windowTitle)
        assertEquals("Merge conflict", content.heading)
        assertTrue(content.summary.contains("Merge of"))
        assertTrue(content.guidance.contains("Abort the merge"))
        assertFalse(content.canLeaveAsIs)
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
