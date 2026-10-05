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
import com.github.karlsabo.devlake.enghub.screen.collectArchiveBinEntries
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeArchiveBinTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun restoredQueuedJobShowsFreshWindowAndUndoWhileMissingJobShowsFailure() = runComposeUiTest {
        val restored = WorktreeArchiveJob(
            repositoryRootPath = "/repos/widgets",
            worktreePath = "/repos/widgets-feature-login",
            branch = "feature/login",
            queueId = "persisted-queue",
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = 1_000,
            stateUpdatedAtEpochMs = 100_000,
            deadlineAtEpochMs = 160_000,
        )
        val jobs = mutableStateOf(listOf(restored))
        val undoRequests = mutableListOf<String>()
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = collectArchiveBinEntries(jobs.value) { 100_000 },
                    actions = WorktreeArchiveBinActions(onUndo = undoRequests::add),
                )
            }
        }
        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("widgets").assertIsDisplayed()
        onNodeWithText("feature/login").assertIsDisplayed()
        onNodeWithText("60 seconds remaining").assertIsDisplayed()
        onNodeWithText("Undo").performClick()
        assertEquals(listOf(restored.worktreePath), undoRequests)
        runOnIdle {
            jobs.value = listOf(
                restored.copy(
                    state = WorktreeArchiveLifecycleState.FAILED,
                    errorMessage = "Queued worktree is no longer registered",
                ),
            )
        }
        onNodeWithText("Removal failed").assertIsDisplayed()
        onNodeWithText("Queued worktree is no longer registered").assertIsDisplayed()
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("60 seconds remaining").assertDoesNotExist()
        onNodeWithText("Retry").assertIsDisplayed()
        onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun persistedFailedJobMapsToGlobalBinActionsWithoutUndo() = runComposeUiTest {
        val failed = WorktreeArchiveJob(
            "/repos/widgets",
            "/repos/widgets-feature-login",
            "feature/login",
            "persisted-failure",
            WorktreeArchiveLifecycleState.FAILED,
            1_000,
            2_000,
            61_000,
            "permission denied",
        )
        val retries = mutableListOf<String>()
        val dismissals = mutableListOf<String>()
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = collectArchiveBinEntries(listOf(failed)) { 100_000 },
                    actions = WorktreeArchiveBinActions(onRetry = retries::add, onDismiss = dismissals::add),
                )
            }
        }
        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("feature/login").assertIsDisplayed()
        onNodeWithText("Removal failed").assertIsDisplayed()
        onNodeWithText("permission denied").assertIsDisplayed()
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("Retry").performClick()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf(failed.worktreePath), retries)
        assertEquals(listOf(failed.worktreePath), dismissals)
    }

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
    fun failedEntryReportsErrorAndOffersRetryAndDismissInsteadOfUndo() = runComposeUiTest {
        val retries = mutableListOf<String>()
        val dismissals = mutableListOf<String>()
        val entries = mutableStateOf(
            listOf(
                WorktreeArchiveBinEntry(
                    "widgets",
                    "feature/login",
                    0,
                    "/repos/login",
                    isFailed = true,
                    errorMessage = "permission denied",
                ),
            ),
        )
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries.value,
                    actions = WorktreeArchiveBinActions(onRetry = retries::add, onDismiss = dismissals::add),
                )
            }
        }
        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Removal failed").assertIsDisplayed()
        onNodeWithText("permission denied").assertIsDisplayed()
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("0 seconds remaining").assertDoesNotExist()
        onNodeWithText("Retry").performClick()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("/repos/login"), retries)
        assertEquals(listOf("/repos/login"), dismissals)
        runOnIdle {
            entries.value = listOf(
                entries.value.single().copy(isFailed = false, isRemoving = true, errorMessage = null),
            )
        }
        onNodeWithText("Being removed").assertIsDisplayed()
        onNodeWithText("Undo").assertIsNotEnabled()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("Dismiss").assertDoesNotExist()
        onNodeWithText("permission denied").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dirtyEntryOffersReviewAndDismissButNeverUndoOrOrdinaryRetry() = runComposeUiTest {
        val requests = mutableListOf<String>()
        val dismissals = mutableListOf<String>()
        setContent {
            MaterialTheme {
                WorktreeArchiveBin(
                    entries = listOf(
                        WorktreeArchiveBinEntry(
                            "widgets",
                            "feature/wip",
                            0,
                            "/repos/wip",
                            needsForceConfirmation = true,
                        ),
                    ),
                    actions = WorktreeArchiveBinActions(
                        onRequestForceConfirmation = requests::add,
                        onDismiss = dismissals::add,
                    ),
                )
            }
        }
        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Confirmation required").assertIsDisplayed()
        onNodeWithText("Undo").assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("/repos/wip"), dismissals)
        onNodeWithText("0 seconds remaining").assertDoesNotExist()
        onNodeWithText("Review force removal").performClick()
        assertEquals(listOf("/repos/wip"), requests)
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
                    actions = WorktreeArchiveBinActions(onUndo = undoRequests::add),
                )
            }
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Undo").assertIsDisplayed().performClick()

        assertEquals(listOf("/repos/widgets-feature-login"), undoRequests)
    }
}
