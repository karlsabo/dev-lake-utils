package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeArchiveBinTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyBinRemainsVisibleAndOpensEmptyState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(entries = emptyList())
            }
        }

        onNodeWithContentDescription("Recycle bin (0)").assertIsDisplayed().performClick()
        onNodeWithText("No worktrees queued for archive").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun queuedBinEntryShowsRepositoryBranchAndCancellationWindow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = listOf(
                        WorktreeArchiveBinEntry(
                            repository = "widgets",
                            branch = "feature/login",
                            remainingSeconds = 60,
                            worktreePath = "/repos/widgets-feature-login",
                        ),
                    ),
                )
            }
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("widgets").assertIsDisplayed()
        onNodeWithText("feature/login").assertIsDisplayed()
        onNodeWithText("60 seconds remaining").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun queuedBinEntryOffersUndoForItsWorktree() = runComposeUiTest {
        val undoRequests = mutableListOf<String>()
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = listOf(
                        WorktreeArchiveBinEntry(
                            repository = "widgets",
                            branch = "feature/login",
                            remainingSeconds = 42,
                            worktreePath = "/repos/widgets-feature-login",
                        ),
                    ),
                    onUndo = undoRequests::add,
                )
            }
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Undo").assertIsDisplayed().performClick()

        assertEquals(listOf("/repos/widgets-feature-login"), undoRequests)
    }
}
