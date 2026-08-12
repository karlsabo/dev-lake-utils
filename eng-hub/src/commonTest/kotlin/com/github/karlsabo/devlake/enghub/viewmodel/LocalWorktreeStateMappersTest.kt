package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalWorktreeStateMappersTest {
    @Test
    fun discoveryDropsEnrichmentWhenParentIsNoLongerVisible() {
        val childPath = "/repo-stacked"
        val discoveredWorktrees = listOf(
            LocalWorktreeUiState(branch = "feature/stacked-pr", path = childPath),
        )
        val previousWorktrees = listOf(
            LocalWorktreeUiState(branch = "feature/base-pr", path = "/repo-base"),
            LocalWorktreeUiState(
                branch = "feature/stacked-pr",
                path = childPath,
                parentBranch = "feature/base-pr",
                needsRebase = true,
            ),
        )

        val child = discoveredWorktrees.withEnrichmentFrom(previousWorktrees).single()

        assertEquals(null, child.parentBranch)
        assertEquals(false, child.needsRebase)
    }
}
