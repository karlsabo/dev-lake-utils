package com.github.karlsabo.devlake.enghub.component

import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeRebaseConflictDialogTest {

    @Test
    fun abortingRebaseConflictDialogCallsBoundaryWithOriginalRequest() {
        val requests = mutableListOf<PendingRebaseConflictResolution>()
        val request = rebaseConflictRequest()

        abortRebaseConflictDialog(request) { requests += it }

        assertEquals(listOf(request), requests)
    }

    @Test
    fun leavingRebaseConflictAsIsDialogCallsBoundaryWithOriginalRequest() {
        val requests = mutableListOf<PendingRebaseConflictResolution>()
        val request = rebaseConflictRequest()

        leaveRebaseConflictAsIsDialog(request) { requests += it }

        assertEquals(listOf(request), requests)
    }

    private fun rebaseConflictRequest(): PendingRebaseConflictResolution = PendingRebaseConflictResolution(
        repoRootPath = "/repos/dev-lake-utils",
        worktreePath = "/repos/dev-lake-utils-feature-stacked-pr",
        parentBranch = "feature/base-pr",
    )
}
