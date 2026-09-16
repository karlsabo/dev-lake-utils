package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getFirstLinkBounds
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.viewmodel.sharedProgressPullRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeWorktreeRowsTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun connectedPullRequestIdentityIsVisibleOnWorktreeRow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/widgets-feature-login",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                        connectedPullRequest = sharedProgressPullRequest(
                            repoFullName = "acme/widgets",
                            branch = "feature/login",
                        ).copy(number = 123, title = "Add login"),
                    ),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        onNodeWithText("PR #123 · Add login").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun connectedPullRequestStatusIsVisibleOnWorktreeRow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/widgets-feature-login",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                        connectedPullRequest = sharedProgressPullRequest(
                            repoFullName = "acme/widgets",
                            branch = "feature/login",
                        ).copy(
                            number = 123,
                            ciStatus = CiStatus.FAILED,
                            ciSummaryText = "7/9 passed, 2 failed",
                            reviewSummaryText = "waiting on 1 reviewer",
                        ),
                    ),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        onNodeWithText("Failed").assertIsDisplayed()
        onNodeWithText("7/9 passed, 2 failed · waiting on 1 reviewer").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun connectedPullRequestInlineLabelOpensUrlWithoutTriggeringParentInteractions() = runComposeUiTest {
        val openedUrls = mutableListOf<String>()
        val toggledRepositories = mutableListOf<String>()
        val emptyActions = emptyPanelActions()
        val panelActions = emptyActions.copy(
            onToggleRepository = toggledRepositories::add,
            worktrees = emptyActions.worktrees.copy(onOpenPullRequest = openedUrls::add),
        )
        setContent {
            MaterialTheme {
                LocalRepositoryRow(
                    state = WorktreeRowsState(
                        repository = LocalRepositoryUiState(
                            name = "widgets",
                            path = "/repos/widgets",
                            isExpanded = true,
                            repositoryIdentity = GitHubRepositoryIdentity("acme", "widgets"),
                            worktrees = listOf(
                                LocalWorktreeUiState("feature/login", "/repos/widgets-feature-login"),
                            ),
                        ),
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                        authoredOpenPullRequests = listOf(
                            sharedProgressPullRequest("acme/widgets", "feature/login").copy(
                                number = 123,
                                title = "Add login",
                                htmlUrl = "https://github.com/acme/widgets/pull/123",
                            ),
                        ),
                    ),
                    panelActions = panelActions,
                    onArchiveRequest = {},
                    onCreateRequest = {},
                )
            }
        }

        val label = onNodeWithContentDescription("Open PR #123 · Add login in browser")
        val linkBounds = requireNotNull(label.getFirstLinkBounds())
        label.performMouseInput { click(linkBounds.center) }

        assertEquals(listOf("https://github.com/acme/widgets/pull/123"), openedUrls)
        assertTrue(toggledRepositories.isEmpty())
        onAllNodesWithText("Open PR in web").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun overflowMenuOpensConnectedPullRequestUrl() = runComposeUiTest {
        val openedUrls = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ConnectedPullRequestWorktreeRow(onOpenPullRequest = openedUrls::add)
            }
        }

        onNodeWithContentDescription("Worktree actions for feature/login").performMouseInput { click() }
        onNodeWithText("Open PR in web").performClick()

        assertEquals(listOf("https://github.com/acme/widgets/pull/123"), openedUrls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickMenuOpensConnectedPullRequestUrlThroughSameAction() = runComposeUiTest {
        val openedUrls = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ConnectedPullRequestWorktreeRow(onOpenPullRequest = openedUrls::add)
            }
        }

        onNodeWithTag("worktree-row-feature/login").performMouseInput { rightClick() }
        onNodeWithText("Open PR in web").performClick()

        assertEquals(listOf("https://github.com/acme/widgets/pull/123"), openedUrls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactOpenShortcutInvokesExistingOpenAction() = runComposeUiTest {
        var openCount = 0
        setContent {
            MaterialTheme {
                WorktreeRow(
                    actions = emptyLocalWorktreeRowActions().copy(onOpen = { openCount += 1 }),
                )
            }
        }

        onNodeWithContentDescription("Open worktree feature/login")
            .assertIsEnabled()
            .performClick()

        assertEquals(1, openCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactOpenShortcutIsDisabledWhileOpeningIsUnavailable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(
                    state = worktreeRowState().copy(
                        setupStatus = WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
                    ),
                )
            }
        }

        onNodeWithContentDescription("Open worktree feature/login").assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactArchiveShortcutContinuesDirtyWorktreeConfirmationFlow() = runComposeUiTest {
        val archiveRequests = mutableListOf<Pair<String, String>>()
        setContent {
            var forceArchiveRequest by remember { mutableStateOf<ForceArchiveWorktreeUiState?>(null) }
            val emptyActions = emptyPanelActions()
            MaterialTheme {
                WorktreePanel(
                    state = WorktreePanelState(
                        localRepositories = listOf(
                            LocalRepositoryUiState(
                                name = "dev-lake-utils",
                                path = "/repos/dev-lake-utils",
                                isExpanded = true,
                                worktrees = listOf(
                                    LocalWorktreeUiState(
                                        branch = "feature/dirty",
                                        path = "/repos/dev-lake-utils-feature-dirty",
                                        isDirty = true,
                                    ),
                                ),
                            ),
                        ),
                        forceArchiveRequest = forceArchiveRequest,
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                    ),
                    actions = emptyActions.copy(
                        worktrees = emptyActions.worktrees.copy(
                            onArchiveWorktree = { repoRootPath, worktreePath ->
                                archiveRequests += repoRootPath to worktreePath
                                forceArchiveRequest = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
                            },
                        ),
                    ),
                )
            }
        }

        onNodeWithContentDescription("Archive worktree feature/dirty").performClick()
        onNodeWithText("Archive Worktree").assertIsDisplayed()
        onNodeWithText("Archive").performClick()

        assertEquals(
            listOf("/repos/dev-lake-utils" to "/repos/dev-lake-utils-feature-dirty"),
            archiveRequests,
        )
        onNodeWithText("Force Archive Worktree").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactArchiveShortcutIsOmittedForRootWorktree() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(
                    state = worktreeRowState().copy(
                        worktree = LocalWorktreeUiState(
                            branch = "main",
                            path = "/repos/dev-lake-utils",
                            isRoot = true,
                        ),
                    ),
                )
            }
        }

        onAllNodesWithText("🗑️").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactArchiveShortcutIsDisabledWhileArchiveIsUnavailable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(
                    state = worktreeRowState().copy(isRebasing = true),
                )
            }
        }

        onNodeWithContentDescription("Archive worktree feature/login").assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mergingWorktreeRowRendersProgressAndDisablesActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorktreeRow(state = worktreeRowState().copy(isMerging = true))
            }
        }

        onNodeWithText("Merging...").assertIsDisplayed()
        onNodeWithContentDescription("Open worktree feature/login").assertIsNotEnabled()
        onNodeWithContentDescription("Archive worktree feature/login").assertIsNotEnabled()
        onNodeWithContentDescription("Worktree actions for feature/login").assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun constrainedRowKeepsProgressAndMenuVisibleWithLongPullRequestDetails() = runComposeUiTest {
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

        val containerBounds = onNodeWithTag("constrained-worktree-container").fetchSemanticsNode().boundsInRoot
        val progressBounds = onNodeWithText("Rebasing...").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val openBounds = onNodeWithContentDescription("Open worktree feature/login")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val archiveBounds = onNodeWithContentDescription("Archive worktree feature/login")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val menuBounds = onNodeWithContentDescription("Worktree actions for feature/login")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(openBounds.left >= containerBounds.left)
        assertTrue(openBounds.top >= containerBounds.top)
        assertTrue(openBounds.right <= containerBounds.right)
        assertTrue(openBounds.bottom <= containerBounds.bottom)
        assertTrue(progressBounds.right <= openBounds.left)
        assertTrue(openBounds.right <= archiveBounds.left)
        assertTrue(archiveBounds.right <= menuBounds.left)
        assertTrue(menuBounds.right <= containerBounds.right)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickingWorktreeRowWhitespaceOpensItsEnabledActionMenu() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                            parentBranch = "main",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                    ),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        onNodeWithTag("worktree-row-feature/login").performMouseInput {
            rightClick(position = Offset(1f, center.y))
        }

        listOf("Open", "Create worktree", "Rebase onto parent", "Merge parent into worktree", "Archive")
            .forEach { label ->
                onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickingBusyWorktreeRowOpensItsDisabledActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/login",
                            path = "/repos/dev-lake-utils-feature-login",
                            parentBranch = "main",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                        isRebasing = true,
                    ),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        onNodeWithTag("worktree-row-feature/login").performMouseInput { rightClick() }

        listOf("Open", "Create worktree", "Rebase onto parent", "Merge parent into worktree", "Archive")
            .forEach { label ->
                onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled()
            }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickPositionsMenuAtPointerRelativeToRowAnchor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize().padding(start = 80.dp, top = 60.dp, end = 80.dp)) {
                    WorktreeRow()
                }
            }
        }
        val row = onNodeWithTag("worktree-row-feature/login")
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val pointerPosition = Offset(120f, rowBounds.height / 2f)

        row.performMouseInput { rightClick(position = pointerPosition) }

        val menuBounds = onNodeWithTag("worktree-action-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(rowBounds.left + pointerPosition.x, menuBounds.left, absoluteTolerance = 1f)
        assertEquals(rowBounds.top + pointerPosition.y, menuBounds.top, absoluteTolerance = 1f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun overflowPositionsMenuBelowItsButtonRelativeToRowAnchor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize().padding(start = 80.dp, top = 60.dp, end = 400.dp)) {
                    WorktreeRow()
                }
            }
        }
        val button = onNodeWithContentDescription("Worktree actions for feature/login")
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot

        button.performMouseInput { click() }

        val menuBounds = onNodeWithTag("worktree-action-menu").fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.left in menuBounds.left..menuBounds.right)
        assertTrue(menuBounds.top in buttonBounds.bottom..(buttonBounds.bottom + 16f))
    }

    @Test
    fun windowPositionIsConvertedToAnchorRelativePosition() {
        val anchorBounds = Rect(left = 80f, top = 60f, right = 720f, bottom = 96f)

        val result = anchorRelativeOffset(anchorBounds, positionInWindow = Offset(688f, 92f))

        assertEquals(Offset(608f, 32f), result)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun worktreeRowReceivesVisibleHighlightWhileHovered() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = LocalWorktreeUiState(
                            branch = "feature/worktree-hover",
                            path = "/repos/dev-lake-utils-feature-worktree-hover",
                        ),
                        setupStatus = null,
                        isArchiving = false,
                    ),
                    actions = emptyLocalWorktreeRowActions(),
                )
            }
        }

        val row = onNodeWithTag("worktree-row-feature/worktree-hover")
        val initialImage = row.captureToImage()
        val initialPixels = IntArray(initialImage.width * initialImage.height)
        initialImage.readPixels(initialPixels)

        row.performMouseInput { moveTo(center) }
        waitForIdle()

        val hoveredImage = row.captureToImage()
        val hoveredPixels = IntArray(hoveredImage.width * hoveredImage.height)
        hoveredImage.readPixels(hoveredPixels)
        assertFalse(initialPixels.contentEquals(hoveredPixels))
    }

    @Composable
    private fun ConnectedPullRequestWorktreeRow(onOpenPullRequest: (String) -> Unit) {
        LocalWorktreeRow(
            state = LocalWorktreeRowState(
                worktree = LocalWorktreeUiState(
                    branch = "feature/login",
                    path = "/repos/widgets-feature-login",
                ),
                setupStatus = null,
                isArchiving = false,
                connectedPullRequest = sharedProgressPullRequest(
                    repoFullName = "acme/widgets",
                    branch = "feature/login",
                ).copy(
                    number = 123,
                    title = "Add login",
                    htmlUrl = "https://github.com/acme/widgets/pull/123",
                ),
            ),
            actions = emptyLocalWorktreeRowActions().copy(onOpenPullRequest = onOpenPullRequest),
        )
    }

    @Composable
    private fun WorktreeRow(
        state: LocalWorktreeRowState = worktreeRowState(),
        actions: LocalWorktreeRowActions = emptyLocalWorktreeRowActions(),
    ) {
        LocalWorktreeRow(state = state, actions = actions)
    }

    private fun worktreeRowState(): LocalWorktreeRowState = LocalWorktreeRowState(
        worktree = LocalWorktreeUiState(
            branch = "feature/login",
            path = "/repos/dev-lake-utils-feature-login",
            parentBranch = "main",
        ),
        setupStatus = null,
        isArchiving = false,
    )
}
