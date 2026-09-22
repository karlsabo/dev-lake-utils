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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
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
    fun childRowShowsOneBaseSpecificUpdateShortcutAndInvokesAutomaticUpdate() = runComposeUiTest {
        var updateCount = 0
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    actions = emptyLocalWorktreeRowActions().copy(onUpdate = { updateCount += 1 }),
                )
            }
        }

        onNodeWithContentDescription("Update feature/login from base main")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, updateCount)
        onAllNodesWithText("⬇️").assertCountEquals(1)
        onAllNodesWithText("🔁").assertCountEquals(0)
        onAllNodesWithText("🔀").assertCountEquals(0)
        onAllNodesWithContentDescription("Rebase worktree feature/login onto main").assertCountEquals(0)
        onAllNodesWithContentDescription("Merge parent main into worktree feature/login").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun threeDotMenuRetainsManualRebaseAndInvokesOnlyRebase() = runComposeUiTest {
        val requests = mutableListOf<IntegrationRequest>()
        setContent {
            MaterialTheme {
                IntegrationRepositoryRow(integrationPanelActions(requests))
            }
        }

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        onNodeWithText("Rebase onto parent").assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(
            listOf(integrationRequest(IntegrationOperation.REBASE)),
            requests,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun threeDotMenuRetainsManualMergeAndInvokesOnlyMerge() = runComposeUiTest {
        val requests = mutableListOf<IntegrationRequest>()
        setContent {
            MaterialTheme {
                IntegrationRepositoryRow(integrationPanelActions(requests))
            }
        }

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        onNodeWithText("Merge parent into worktree").assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(
            listOf(integrationRequest(IntegrationOperation.MERGE)),
            requests,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickMenuRetainsManualActionsAndInvokesOnlyTheSelectedOperation() = runComposeUiTest {
        val requests = mutableListOf<IntegrationRequest>()
        setContent {
            MaterialTheme {
                IntegrationRepositoryRow(integrationPanelActions(requests))
            }
        }
        val row = onNodeWithTag("worktree-row-feature/login")

        row.performMouseInput { rightClick() }
        onNodeWithText("Rebase onto parent").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(integrationRequest(IntegrationOperation.REBASE)), requests)

        row.performMouseInput { rightClick() }
        onNodeWithText("Merge parent into worktree").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(
            listOf(
                integrationRequest(IntegrationOperation.REBASE),
                integrationRequest(IntegrationOperation.MERGE),
            ),
            requests,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun childUpdateShortcutTooltipNamesItsBase() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow()
            }
        }

        onNodeWithContentDescription("Update feature/login from base main")
            .performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)

        onNodeWithText("Update from base main").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun childUpdateShortcutIsHiddenWithoutAnInferredBase() = runComposeUiTest {
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

        onAllNodesWithText("⬇️").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun childUpdateShortcutIsDisabledDuringEveryExclusiveOperation() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                    ),
                )
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isArchiving = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isUpdating = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isRebasing = true))
                IntegrationShortcutRow(state = integrationShortcutRowState().copy(isMerging = true))
            }
        }

        val updateShortcuts = onAllNodesWithContentDescription("Update feature/login from base main")
        updateShortcuts.assertCountEquals(5)
        repeat(5) { index -> updateShortcuts[index].assertIsNotEnabled() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun originDefaultBranchRetainsItsOriginSpecificUpdateShortcut() = runComposeUiTest {
        setContent {
            MaterialTheme {
                IntegrationShortcutRow(
                    state = integrationShortcutRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "main",
                            path = "/repos/dev-lake-utils",
                            parentBranch = "develop",
                            canUpdateFromOrigin = true,
                        ),
                    ),
                )
            }
        }

        onNodeWithContentDescription("Update worktree main from origin")
            .assertIsDisplayed()
            .assertIsEnabled()
        onAllNodesWithContentDescription("Update main from base develop").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun constrainedRowRendersSingleIntegrationControlWithLongPullRequestDetails() = runComposeUiTest {
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
        val update = assertControlFitsWithin(container, "Update feature/login from base main")
        val open = assertControlFitsWithin(container, "Open worktree feature/login")
        val archive = assertControlFitsWithin(container, "Archive worktree feature/login")
        val menu = assertControlFitsWithin(container, "Worktree actions for feature/login")

        assertTrue(progress.right <= update.left)
        assertTrue(update.right <= open.left)
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

    @Composable
    private fun IntegrationRepositoryRow(panelActions: WorktreePanelActions) {
        LocalRepositoryRow(
            state = WorktreeRowsState(
                repository = LocalRepositoryUiState(
                    name = "dev-lake-utils",
                    path = REPOSITORY_PATH,
                    isExpanded = true,
                    worktrees = listOf(integrationShortcutRowState().worktree),
                ),
                setupStatuses = emptyMap(),
                archivingWorktreePaths = emptySet(),
            ),
            panelActions = panelActions,
            onArchiveRequest = {},
            onCreateRequest = {},
        )
    }

    private fun integrationPanelActions(requests: MutableList<IntegrationRequest>): WorktreePanelActions {
        val actions = emptyPanelActions()
        return actions.copy(
            worktrees = actions.worktrees.copy(
                onUpdateFromParent = { repositoryPath, worktreePath, parentBranch ->
                    requests += IntegrationRequest(
                        IntegrationOperation.UPDATE,
                        repositoryPath,
                        worktreePath,
                        parentBranch,
                    )
                },
                onRebaseOntoParent = { repositoryPath, worktreePath, parentBranch ->
                    requests += IntegrationRequest(
                        IntegrationOperation.REBASE,
                        repositoryPath,
                        worktreePath,
                        parentBranch,
                    )
                },
                onMergeOntoParent = { repositoryPath, worktreePath, parentBranch ->
                    requests += IntegrationRequest(
                        IntegrationOperation.MERGE,
                        repositoryPath,
                        worktreePath,
                        parentBranch,
                    )
                },
            ),
        )
    }

    private fun integrationRequest(operation: IntegrationOperation) = IntegrationRequest(
        operation = operation,
        repositoryPath = REPOSITORY_PATH,
        worktreePath = WORKTREE_PATH,
        parentBranch = "main",
    )

    private fun integrationShortcutRowState(): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/login",
            path = WORKTREE_PATH,
            parentBranch = "main",
        ),
        setupStatus = null,
        isArchiving = false,
    )

    private data class IntegrationRequest(
        val operation: IntegrationOperation,
        val repositoryPath: String,
        val worktreePath: String,
        val parentBranch: String,
    )

    private enum class IntegrationOperation {
        UPDATE,
        REBASE,
        MERGE,
    }

    private companion object {
        const val REPOSITORY_PATH = "/repos/dev-lake-utils"
        const val WORKTREE_PATH = "/repos/dev-lake-utils-feature-login"
    }
}
