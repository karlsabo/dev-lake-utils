package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorktreeArchiveAnimationTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun persistedQueueExitsRowWhileRejectedQueueLeavesItVisible() = runComposeUiTest {
        mainClock.autoAdvance = false
        val path = "/repos/widgets-login"
        var queuedPaths by mutableStateOf(emptySet<String>())
        var newlyQueuedPaths by mutableStateOf(emptySet<String>())
        var acceptsQueue = false
        var requests = 0
        val actions = emptyPanelActions()
        setContent {
            MaterialTheme {
                LocalRepositoryRow(
                    state = WorktreeRowsState(
                        repository = LocalRepositoryUiState(
                            name = "widgets",
                            path = "/repos/widgets",
                            isExpanded = true,
                            worktrees = listOf(LocalWorktreeUiState("feature/login", path, isDirty = false)),
                        ),
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        queuedArchiveWorktreePaths = queuedPaths,
                        newlyQueuedArchiveWorktreePaths = newlyQueuedPaths,
                    ),
                    panelActions = actions.copy(
                        worktrees = actions.worktrees.copy(onArchiveWorktree = { _, _ ->
                            requests++
                            if (acceptsQueue) {
                                newlyQueuedPaths = setOf(path)
                                queuedPaths = setOf(path)
                            }
                        }),
                    ),
                    onCreateRequest = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Archive worktree feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertIsDisplayed()
        assertEquals(1, requests)
        assertTrue(queuedPaths.isEmpty())

        acceptsQueue = true
        onNodeWithContentDescription("Archive worktree feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertExists()
        onNodeWithContentDescription("Archive worktree feature/login").assertIsNotEnabled()
        mainClock.advanceTimeBy(260)
        onNodeWithTag("worktree-row-feature/login").assertDoesNotExist()
        assertEquals(2, requests)

        queuedPaths = emptySet()
        newlyQueuedPaths = emptySet()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertIsDisplayed()
        queuedPaths = setOf(path)
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun completedExitStaysHiddenAfterCollapseAndExpandUntilUndo() = runComposeUiTest {
        mainClock.autoAdvance = false
        val path = "/repos/widgets-login"
        var expanded by mutableStateOf(true)
        var queuedPaths by mutableStateOf(emptySet<String>())
        var newlyQueuedPaths by mutableStateOf(emptySet<String>())
        val actions = emptyPanelActions()
        setContent {
            MaterialTheme {
                LocalRepositoryRow(
                    state = WorktreeRowsState(
                        repository = LocalRepositoryUiState(
                            name = "widgets",
                            path = "/repos/widgets",
                            isExpanded = expanded,
                            worktrees = listOf(LocalWorktreeUiState("feature/login", path, isDirty = false)),
                        ),
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        queuedArchiveWorktreePaths = queuedPaths,
                        newlyQueuedArchiveWorktreePaths = newlyQueuedPaths,
                    ),
                    panelActions = actions.copy(
                        onToggleRepository = { expanded = !expanded },
                        worktrees = actions.worktrees.copy(onArchiveWorktree = { _, _ ->
                            newlyQueuedPaths = setOf(path)
                            queuedPaths = setOf(path)
                        }),
                    ),
                    onCreateRequest = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Archive worktree feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertExists()
        mainClock.advanceTimeBy(260)
        onNodeWithTag("worktree-row-feature/login").assertDoesNotExist()

        onNodeWithContentDescription("Collapse widgets").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Expand widgets").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertDoesNotExist()
        mainClock.advanceTimeBy(260)
        onNodeWithTag("worktree-row-feature/login").assertDoesNotExist()

        queuedPaths = emptySet()
        newlyQueuedPaths = emptySet()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/login").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun childReturnsToRootIndentOnlyAfterQueuedParentExitFinishes() = runComposeUiTest {
        mainClock.autoAdvance = false
        val parentPath = "/repos/widgets-parent"
        var queuedPaths by mutableStateOf(emptySet<String>())
        var newlyQueuedPaths by mutableStateOf(emptySet<String>())
        val actions = emptyPanelActions()
        setContent {
            MaterialTheme {
                LocalRepositoryRow(
                    state = WorktreeRowsState(
                        repository = LocalRepositoryUiState(
                            name = "widgets",
                            path = "/repos/widgets",
                            isExpanded = true,
                            worktrees = listOf(
                                LocalWorktreeUiState(
                                    "feature/child",
                                    "/repos/widgets-child",
                                    parentBranch = "feature/parent",
                                ),
                                LocalWorktreeUiState("feature/parent", parentPath),
                            ),
                        ),
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        queuedArchiveWorktreePaths = queuedPaths,
                        newlyQueuedArchiveWorktreePaths = newlyQueuedPaths,
                    ),
                    panelActions = actions,
                    onCreateRequest = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()
        val parentIndent = onNodeWithText("feature/parent").getUnclippedBoundsInRoot().left
        val childIndent = onNodeWithText("feature/child").getUnclippedBoundsInRoot().left
        assertTrue(childIndent > parentIndent)

        newlyQueuedPaths = setOf(parentPath)
        queuedPaths = setOf(parentPath)
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/parent").assertExists()
        assertEquals(childIndent, onNodeWithText("feature/child").getUnclippedBoundsInRoot().left)

        mainClock.advanceTimeBy(260)
        onNodeWithTag("worktree-row-feature/parent").assertDoesNotExist()
        assertEquals(parentIndent, onNodeWithText("feature/child").getUnclippedBoundsInRoot().left)
        assertNotEquals(childIndent, onNodeWithText("feature/child").getUnclippedBoundsInRoot().left)

        queuedPaths = emptySet()
        newlyQueuedPaths = emptySet()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("worktree-row-feature/parent").assertIsDisplayed()
        assertEquals(childIndent, onNodeWithText("feature/child").getUnclippedBoundsInRoot().left)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun binPulsesOnceOnlyForNewPersistedQueueNotRestoredOrRejectedEntry() = runComposeUiTest {
        mainClock.autoAdvance = false
        val entry = WorktreeArchiveBinEntry(
            "widgets",
            "feature/login",
            60,
            "/repos/widgets-login",
            queueId = "queued-1",
        )
        var entries by mutableStateOf(listOf(entry))
        setContent {
            MaterialTheme {
                Box(Modifier.size(80.dp).testTag("bin-snapshot")) {
                    WorktreeArchiveBin(entries = entries)
                }
            }
        }
        mainClock.advanceTimeByFrame()
        val baseline = onNodeWithTag("bin-snapshot").captureToImage().pixels()
        mainClock.advanceTimeBy(400)
        assertTrue(baseline.contentEquals(onNodeWithTag("bin-snapshot").captureToImage().pixels()))

        // A rejected write leaves the event absent even if the persisted bin already has entries.
        entries = entries + entry.copy(worktreePath = "/repos/widgets-search", queueId = "restored-2")
        mainClock.advanceTimeByFrame()
        val beforeQueue = onNodeWithTag("bin-snapshot").captureToImage().pixels()
        mainClock.advanceTimeBy(400)
        assertTrue(beforeQueue.contentEquals(onNodeWithTag("bin-snapshot").captureToImage().pixels()))

        entries = entries.map { it.copy(isNewlyQueued = it.queueId == "queued-1") }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(160)
        assertFalse(beforeQueue.contentEquals(onNodeWithTag("bin-snapshot").captureToImage().pixels()))
        mainClock.advanceTimeBy(200)
        assertTrue(beforeQueue.contentEquals(onNodeWithTag("bin-snapshot").captureToImage().pixels()))
        mainClock.advanceTimeBy(400)
        assertTrue(beforeQueue.contentEquals(onNodeWithTag("bin-snapshot").captureToImage().pixels()))
        onNodeWithContentDescription("Recycle bin (2)").assertIsDisplayed()
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }
}
