package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.viewmodel.sharedProgressPullRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.github.CiStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreeIntegrationShortcutsTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactRebaseShortcutIsVisibleOnlyWhenParentBranchIsKnown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                        ),
                    ),
                )
            }
        }

        onAllNodesWithContentDescription("Rebase worktree feature/login onto main").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactRebaseShortcutIsEnabledWhenRebaseIsNotNeeded() = runComposeUiTest {
        var rebaseCount = 0
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                            parentBranch = "main",
                            needsRebase = false,
                        ),
                    ),
                    actions = emptyLocalWorktreeRowActions().copy(
                        onRebaseOntoParent = { rebaseCount += 1 },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Rebase worktree feature/login onto main")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, rebaseCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactRebaseShortcutIsDisabledDuringOtherOperations() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                    ),
                )
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isArchiving = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isRebasing = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isMerging = true))
            }
        }

        val rebaseShortcuts = onAllNodesWithContentDescription("Rebase worktree feature/login onto main")
        rebaseShortcuts.assertCountEquals(4)
        repeat(4) { index -> rebaseShortcuts[index].assertIsNotEnabled() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactRebaseShortcutInvokesSameActionAsMenu() = runComposeUiTest {
        val rebaseRequests = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    actions = emptyLocalWorktreeRowActions().copy(
                        onRebaseOntoParent = { rebaseRequests += Unit },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Rebase worktree feature/login onto main").performClick()
        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        onNodeWithText("Rebase onto parent").performClick()

        assertEquals(2, rebaseRequests.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactMergeShortcutIsVisibleOnlyWhenParentBranchIsKnown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                        ),
                    ),
                )
            }
        }

        onAllNodesWithContentDescription("Merge parent main into worktree feature/login").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactMergeShortcutIsEnabledWhenRebaseIsNotNeeded() = runComposeUiTest {
        var mergeCount = 0
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                            parentBranch = "main",
                            needsRebase = false,
                        ),
                    ),
                    actions = emptyLocalWorktreeRowActions().copy(
                        onMergeOntoParent = { mergeCount += 1 },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Merge parent main into worktree feature/login")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, mergeCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactMergeShortcutIsDisabledDuringOtherOperations() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                    ),
                )
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isArchiving = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isRebasing = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isMerging = true))
            }
        }

        val mergeShortcuts = onAllNodesWithContentDescription("Merge parent main into worktree feature/login")
        mergeShortcuts.assertCountEquals(4)
        repeat(4) { index -> mergeShortcuts[index].assertIsNotEnabled() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactMergeShortcutInvokesSameActionAsMenu() = runComposeUiTest {
        val mergeRequests = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    actions = emptyLocalWorktreeRowActions().copy(
                        onMergeOntoParent = { mergeRequests += Unit },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Merge parent main into worktree feature/login").performClick()
        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        onNodeWithText("Merge parent into worktree").performClick()

        assertEquals(2, mergeRequests.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactRebaseAndMergeShortcutsUseDistinguishableIcons() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow()
            }
        }

        onNodeWithText("🔁").assertIsDisplayed()
        onNodeWithText("🔀").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun constrainedRowRendersBothIntegrationControlsWithLongPullRequestDetails() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(width = 480.dp, height = 80.dp)
                        .testTag("constrained-worktree-container"),
                ) {
                    LocalWorktreeRow(
                        state = LocalWorktreeRowState(
                            worktree = LocalWorktreeUiState(
                                branch = "feature/login",
                                path = "/repos/widgets-feature-login",
                                parentBranch = "main",
                            ),
                            setupStatus = null,
                            isArchiving = false,
                            isRebasing = true,
                            connectedPullRequest = sharedProgressPullRequest(
                                repoFullName = "acme/widgets",
                                branch = "feature/login",
                            ).copy(
                                number = 123,
                                title = "Add a long login flow title that cannot fit in this constrained row",
                                ciStatus = CiStatus.FAILED,
                                ciSummaryText = "77/99 checks passed and 22 failed after several retries",
                                reviewSummaryText = "waiting on many required reviewers",
                            ),
                        ),
                        actions = emptyLocalWorktreeRowActions(),
                    )
                }
            }
        }

        val container = onNodeWithTag("constrained-worktree-container").fetchSemanticsNode().boundsInRoot
        val progress = onNodeWithText("Rebasing...").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rebase = assertControlFitsWithin(container, "Rebase worktree feature/login onto main")
        val merge = assertControlFitsWithin(container, "Merge parent main into worktree feature/login")
        val open = assertControlFitsWithin(container, "Open worktree feature/login")
        val archive = assertControlFitsWithin(container, "Archive worktree feature/login")
        val menu = assertControlFitsWithin(container, "Worktree actions for feature/login")

        assertTrue(progress.right <= rebase.left)
        assertTrue(rebase.right <= merge.left)
        assertTrue(merge.right <= open.left)
        assertTrue(open.right <= archive.left)
        assertTrue(archive.right <= menu.left)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.assertControlFitsWithin(container: Rect, description: String): Rect {
        val bounds = onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(bounds.left >= container.left, "$description exceeds left edge")
        assertTrue(bounds.top >= container.top, "$description exceeds top edge")
        assertTrue(bounds.right <= container.right, "$description exceeds right edge")
        assertTrue(bounds.bottom <= container.bottom, "$description exceeds bottom edge")
        return bounds
    }

    @Composable
    private fun IntegrationShortcutRow(
        state: LocalWorktreeRowState = integrationShortcutRowState(),
        actions: LocalWorktreeRowActions = emptyLocalWorktreeRowActions(),
    ) {
        LocalWorktreeRow(state = state, actions = actions)
    }

    private fun integrationShortcutRowState(): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/login",
            path = "/repos/dev-lake-utils-feature-login",
            parentBranch = "main",
        ),
        setupStatus = null,
        isArchiving = false,
    )
}
