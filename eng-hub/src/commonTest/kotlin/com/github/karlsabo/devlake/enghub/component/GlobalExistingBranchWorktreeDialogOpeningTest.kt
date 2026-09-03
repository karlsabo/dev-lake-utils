package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

class GlobalExistingBranchWorktreeDialogOpeningTest {
    @Test
    fun blankGlobalSearchDoesNotListPreviouslyDiscoveredBranches() {
        val discovery = GlobalExistingBranchDiscoveryUiState(
            repositories = listOf(
                ExistingBranchDiscoveryUiState(
                    repoRootPath = REPO_PATH,
                    branches = listOf("feature/previous-search"),
                ),
            ),
        )

        assertTrue(globalExistingWorktreeResults(discovery, " ").isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun existingBranchDialogKeepsActionsVisibleWhenResultsFillACompactWindow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(width = 400.dp, height = 300.dp)) {
                    GlobalExistingBranchWorktreeDialogContent(
                        request = PendingGlobalCreateWorktree(existingBranchQuery = "feature"),
                        discovery = GlobalExistingBranchDiscoveryUiState(
                            repositories = listOf(
                                ExistingBranchDiscoveryUiState(
                                    repoRootPath = REPO_PATH,
                                    branches = List(20) { index -> "feature/branch-$index" },
                                ),
                            ),
                        ),
                        onRequestChange = {},
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Branch and pull request results scrollbar").assertIsDisplayed()
        onNodeWithText("Use Existing").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }

    private companion object {
        const val REPO_PATH = "/repos/dev-lake-utils"
    }
}
