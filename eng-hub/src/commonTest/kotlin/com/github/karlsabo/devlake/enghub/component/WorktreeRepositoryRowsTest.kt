package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.viewmodel.sharedProgressPullRequest
import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorktreeRepositoryRowsTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun repositoryRowConnectsAuthoredPullRequestByRepositoryAndBranch() = runComposeUiTest {
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
                            sharedProgressPullRequest("acme/widgets", "feature/login")
                                .copy(number = 123, title = "Add login"),
                        ),
                    ),
                    panelActions = emptyPanelActions(),
                    onArchiveRequest = {},
                    onCreateRequest = {},
                )
            }
        }

        onNodeWithText("PR #123 · Add login").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun repositoryHeaderReceivesVisibleHighlightWhileHovered() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LocalRepositoryRow(
                    state = WorktreeRowsState(
                        repository = LocalRepositoryUiState(
                            name = "dev-lake-utils",
                            path = "/repos/dev-lake-utils",
                        ),
                        setupStatuses = emptyMap(),
                        archivingWorktreePaths = emptySet(),
                    ),
                    panelActions = emptyPanelActions(),
                    onArchiveRequest = {},
                    onCreateRequest = {},
                )
            }
        }

        val header = onNodeWithTag("repository-header-dev-lake-utils")
        val initialImage = header.captureToImage()
        val initialPixels = IntArray(initialImage.width * initialImage.height)
        initialImage.readPixels(initialPixels)

        header.performMouseInput { moveTo(center) }
        waitForIdle()

        val hoveredImage = header.captureToImage()
        val hoveredPixels = IntArray(hoveredImage.width * hoveredImage.height)
        hoveredImage.readPixels(hoveredPixels)
        assertFalse(initialPixels.contentEquals(hoveredPixels))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickingRepositoryHeaderDoesNotOpenAnActionMenu() = runComposeUiTest {
        setContent { RepositoryRow(onToggleRepository = {}) }

        onNodeWithTag("repository-header-dev-lake-utils").performMouseInput { rightClick() }

        onAllNodesWithText("Create worktree").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun doubleClickingCollapsedRepositoryHeaderContentExpandsRepository() = runComposeUiTest {
        val toggledPaths = mutableListOf<String>()
        var isExpanded by mutableStateOf(false)
        setContent {
            RepositoryRow(
                isExpanded = isExpanded,
                onToggleRepository = {
                    toggledPaths += it
                    isExpanded = true
                },
            )
        }

        onNodeWithTag("repository-header-content-dev-lake-utils").performMouseInput { doubleClick() }

        onNodeWithContentDescription("Collapse dev-lake-utils").assertIsDisplayed()
        assertEquals(listOf("/repos/dev-lake-utils"), toggledPaths)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun doubleClickingRepositoryHeaderWhitespaceExpandsRepository() = runComposeUiTest {
        val toggledPaths = mutableListOf<String>()
        var isExpanded by mutableStateOf(false)
        setContent {
            RepositoryRow(
                isExpanded = isExpanded,
                onToggleRepository = {
                    toggledPaths += it
                    isExpanded = true
                },
            )
        }

        onNodeWithTag("repository-header-dev-lake-utils").performMouseInput {
            doubleClick(position = Offset(center.x, 1f))
        }

        onNodeWithContentDescription("Collapse dev-lake-utils").assertIsDisplayed()
        assertEquals(listOf("/repos/dev-lake-utils"), toggledPaths)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun singleClickingRepositoryHeaderContentDoesNotToggleRepository() = runComposeUiTest {
        val toggledPaths = mutableListOf<String>()
        setContent { RepositoryRow(onToggleRepository = toggledPaths::add) }

        onNodeWithTag("repository-header-content-dev-lake-utils").performMouseInput { click() }

        assertEquals(emptyList(), toggledPaths)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun doubleClickingRepositoryToggleOnlyRunsItsSingleClickAction() = runComposeUiTest {
        val toggledPaths = mutableListOf<String>()
        setContent { RepositoryRow(onToggleRepository = toggledPaths::add) }

        onNodeWithContentDescription("Expand dev-lake-utils").performMouseInput { doubleClick() }

        assertEquals(
            listOf("/repos/dev-lake-utils", "/repos/dev-lake-utils"),
            toggledPaths,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun doubleClickingRepositoryOverflowDoesNotToggleRepository() = runComposeUiTest {
        val toggledPaths = mutableListOf<String>()
        setContent { RepositoryRow(onToggleRepository = toggledPaths::add) }

        onNodeWithContentDescription("Repository actions for dev-lake-utils").performMouseInput { doubleClick() }

        assertEquals(emptyList(), toggledPaths)
    }

    @Composable
    private fun RepositoryRow(
        isExpanded: Boolean = false,
        onToggleRepository: (String) -> Unit,
    ) {
        MaterialTheme {
            LocalRepositoryRow(
                state = WorktreeRowsState(
                    repository = LocalRepositoryUiState(
                        name = "dev-lake-utils",
                        path = "/repos/dev-lake-utils",
                        isExpanded = isExpanded,
                    ),
                    setupStatuses = emptyMap(),
                    archivingWorktreePaths = emptySet(),
                ),
                panelActions = emptyPanelActions().copy(onToggleRepository = onToggleRepository),
                onArchiveRequest = {},
                onCreateRequest = {},
            )
        }
    }
}
