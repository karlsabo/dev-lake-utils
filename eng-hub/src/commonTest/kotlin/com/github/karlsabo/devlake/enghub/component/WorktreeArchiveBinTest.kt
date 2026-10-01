package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
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
    fun removingBinEntryDisablesUndoAndExplainsWhy() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = listOf(
                        WorktreeArchiveBinEntry(
                            repository = "widgets",
                            branch = "feature/login",
                            remainingSeconds = 0,
                            worktreePath = "/repos/widgets-feature-login",
                            isRemoving = true,
                        ),
                    ),
                )
            }
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Being removed").assertIsDisplayed()
        onNodeWithText("Undo").assertIsNotEnabled().performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("This worktree is being removed and can no longer be canceled.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun completedEntryDisappearsFromOpenBinWithoutCompletedHistory() = runComposeUiTest {
        val entries = mutableStateOf(
            listOf(
                WorktreeArchiveBinEntry("widgets", "feature/login", 0, "/repos/login", isRemoving = true),
                WorktreeArchiveBinEntry("widgets", "feature/search", 42, "/repos/search"),
            ),
        )
        setContent { MaterialTheme { WorktreeArchiveBin(entries = entries.value) } }
        onNodeWithContentDescription("Recycle bin (2)").performClick()
        onNodeWithText("Being removed").assertIsDisplayed()

        runOnIdle { entries.value = entries.value.drop(1) }
        onNodeWithContentDescription("Recycle bin (1)").assertIsDisplayed()
        onNodeWithText("feature/login").assertDoesNotExist()
        onNodeWithText("Being removed").assertDoesNotExist()
        onNodeWithText("feature/search").assertIsDisplayed()
        onNodeWithText("42 seconds remaining").assertIsDisplayed()

        runOnIdle { entries.value = emptyList() }
        onNodeWithContentDescription("Recycle bin (0)").assertIsDisplayed()
        onNodeWithText("No worktrees queued for archive").assertIsDisplayed()
        onNodeWithText("Undo").assertDoesNotExist()
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
