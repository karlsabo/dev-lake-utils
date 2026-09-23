package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

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
}
