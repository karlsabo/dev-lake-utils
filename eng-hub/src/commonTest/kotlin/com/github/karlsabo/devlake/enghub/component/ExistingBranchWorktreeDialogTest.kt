package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExistingBranchWorktreeDialogTest {
    @Test
    fun existingBranchSearchFuzzyMatchesAndRanksSubstringFirst() {
        val branches = listOf("feature/other", "feature/existing-worktree", "feature/existing-worktre")

        assertEquals(
            listOf("feature/existing-worktree", "feature/existing-worktre"),
            filterExistingBranches(branches, "existing-worktree"),
        )
    }

    @Test
    fun numericBranchAndPullRequestResultsRemainDistinct() {
        val pullRequest = pullRequestResult(number = 123, branch = "feature/pr-123")
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            branches = listOf("123"),
            originBranches = listOf("feature/pr-123"),
            originBranchRefreshSucceeded = true,
            pullRequestQuery = "123",
            pullRequest = pullRequest,
        )

        assertEquals(
            listOf(ExistingBranchWorktreeResult(REPO_PATH, "123"), pullRequest),
            existingWorktreeResults(discovery, "123"),
        )
    }

    @Test
    fun globalNumericSearchPreservesConfiguredRepositoryBranchAndPullRequestResults() {
        val pullRequest = pullRequestResult(
            repoRootPath = ENGINEERING_DOCS_PATH,
            number = 456,
            branch = "feature/pr-search",
            repositoryFullName = "owner/engineering-docs",
        )
        val discovery = GlobalExistingBranchDiscoveryUiState(
            repositories = listOf(
                ExistingBranchDiscoveryUiState(
                    repoRootPath = REPO_PATH,
                    branches = listOf("456"),
                ),
                ExistingBranchDiscoveryUiState(
                    repoRootPath = ENGINEERING_DOCS_PATH,
                    originBranches = listOf("feature/pr-search"),
                    originBranchRefreshSucceeded = true,
                    pullRequestQuery = "456",
                    pullRequest = pullRequest,
                ),
            ),
        )

        assertEquals(
            listOf(
                ExistingBranchWorktreeResult(REPO_PATH, "456"),
                pullRequest,
            ),
            globalExistingWorktreeResults(discovery, "456"),
        )
    }

    @Test
    fun globalExistingBranchSearchReturnsConfiguredRepositoryBranch() {
        val discovery = GlobalExistingBranchDiscoveryUiState(
            repositories = listOf(
                ExistingBranchDiscoveryUiState(
                    repoRootPath = REPO_PATH,
                    branches = listOf("feature/other"),
                ),
                ExistingBranchDiscoveryUiState(
                    repoRootPath = ENGINEERING_DOCS_PATH,
                    branches = listOf("feature/doc-search"),
                ),
            ),
        )

        assertEquals(
            listOf(ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, "feature/doc-search")),
            globalExistingWorktreeResults(discovery, "doc-search"),
        )
    }

    @Test
    fun globalExistingBranchSearchKeepsSameBranchFromEachRepositoryInStableOrder() {
        val branch = "release/123"
        val discovery = GlobalExistingBranchDiscoveryUiState(
            repositories = listOf(
                ExistingBranchDiscoveryUiState(
                    repoRootPath = ENGINEERING_DOCS_PATH,
                    branches = listOf(branch),
                ),
                ExistingBranchDiscoveryUiState(
                    repoRootPath = REPO_PATH,
                    branches = listOf(branch),
                ),
            ),
        )

        assertEquals(
            listOf(
                ExistingBranchWorktreeResult(REPO_PATH, branch),
                ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch),
            ),
            globalExistingWorktreeResults(discovery, branch),
        )
    }

    @Test
    fun existingBranchResultCarriesDiscoveredWorktreePath() {
        val branch = "feature/already-local"
        val worktreePath = "/tmp/dev-lake-utils-already-local"
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            branches = listOf(branch),
            worktreePathsByBranch = mapOf(branch to worktreePath),
        )

        assertEquals(
            listOf(ExistingBranchWorktreeResult(REPO_PATH, branch, worktreePath)),
            existingWorktreeResults(discovery, "already-local"),
        )
    }

    @Test
    fun sameBranchSelectionIsScopedToRepositoryIdentity() {
        val branch = "release/123"
        val selected = ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch)
        val currentResults = listOf(
            ExistingBranchWorktreeResult(REPO_PATH, branch),
            ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch),
        )

        assertEquals(selected, selectedExistingWorktreeResult(selected, currentResults))
    }

    @Test
    fun exactPullRequestMatchRanksBeforeFuzzyBranchMatch() {
        val pullRequest = pullRequestResult(number = 123, branch = "feature/pr-123")
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            branches = listOf("124"),
            originBranches = listOf("feature/pr-123"),
            originBranchRefreshSucceeded = true,
            pullRequestQuery = "123",
            pullRequest = pullRequest,
        )

        assertEquals(
            listOf(pullRequest, ExistingBranchWorktreeResult(REPO_PATH, "124")),
            existingWorktreeResults(discovery, "123"),
        )
    }

    @Test
    fun pullRequestResultIsNotDeduplicatedAgainstSameBranchResult() {
        val pullRequest = pullRequestResult(number = 123, branch = "feature/pr-123")
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            branches = listOf("feature/pr-123"),
            originBranches = listOf("feature/pr-123"),
            originBranchRefreshSucceeded = true,
            pullRequestQuery = "123",
            pullRequest = pullRequest,
        )

        assertEquals(
            listOf(ExistingBranchWorktreeResult(REPO_PATH, "feature/pr-123"), pullRequest),
            existingWorktreeResults(discovery, "123"),
        )
    }

    @Test
    fun selectedResultIsRetainedByIdentityWhenResultLabelsChange() {
        val selected = pullRequestResult(
            number = 123,
            branch = "feature/pr-123",
            repositoryFullName = "old-owner/dev-lake-utils",
        )
        val current = pullRequestResult(
            number = 123,
            branch = "feature/pr-123",
            repositoryFullName = "owner/dev-lake-utils",
        )

        assertEquals(current, selectedExistingWorktreeResult(selected, listOf(current)))
    }

    @Test
    fun localBranchCannotMakeDeletedRemotePullRequestHeadSelectable() {
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            branches = listOf("main", "feature/missing"),
            originBranches = listOf("main"),
            originBranchRefreshSucceeded = true,
            pullRequestQuery = "123",
            pullRequest = ExistingPullRequestWorktreeResult(
                repoRootPath = REPO_PATH,
                repositoryFullName = "owner/dev-lake-utils",
                number = 123,
                branch = "feature/missing",
            ),
        )

        assertTrue(existingWorktreeResults(discovery, "123").isEmpty())
    }

    @Test
    fun staleOriginBranchListingDoesNotDiscardNewerPullRequestResult() {
        val pullRequest = ExistingPullRequestWorktreeResult(
            repoRootPath = REPO_PATH,
            repositoryFullName = "owner/dev-lake-utils",
            number = 123,
            branch = "feature/pr-worktree",
        )
        val discovery = ExistingBranchDiscoveryUiState(
            repoRootPath = REPO_PATH,
            originBranches = listOf("main"),
            originBranchRefreshSucceeded = false,
            pullRequestQuery = "123",
            pullRequest = pullRequest,
        )

        assertEquals(listOf(pullRequest), existingWorktreeResults(discovery, "123"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun numericBranchAndPullRequestResultsRenderSeparateLabels() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = existingBranchRequest().copy(existingBranchQuery = "123"),
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = REPO_PATH,
                        branches = listOf("123"),
                        originBranches = listOf("feature/pr-123"),
                        originBranchRefreshSucceeded = true,
                        pullRequestQuery = "123",
                        pullRequest = pullRequestResult(number = 123, branch = "feature/pr-123"),
                    ),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Branch · dev-lake-utils · 123").assertIsDisplayed()
        onNodeWithText("PR #123 · owner/dev-lake-utils · feature/pr-123").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalExistingBranchDialogShowsPullRequestResultsAndLoading() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = PendingGlobalCreateWorktree(existingBranchQuery = "456"),
                    discovery = GlobalExistingBranchDiscoveryUiState(
                        repositories = listOf(
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = ENGINEERING_DOCS_PATH,
                                originBranches = listOf("feature/pr-search"),
                                originBranchRefreshSucceeded = true,
                                pullRequestQuery = "456",
                                pullRequest = pullRequestResult(
                                    repoRootPath = ENGINEERING_DOCS_PATH,
                                    number = 456,
                                    branch = "feature/pr-search",
                                    repositoryFullName = "owner/engineering-docs",
                                ),
                            ),
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = REPO_PATH,
                                isPullRequestLoading = true,
                            ),
                        ),
                    ),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("PR #456 · owner/engineering-docs · feature/pr-search").assertIsDisplayed()
        onNodeWithContentDescription("Loading pull request").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalExistingBranchDialogHidesUnsupportedForkMessageWhenAnotherRepositoryHasPullRequest() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = PendingGlobalCreateWorktree(existingBranchQuery = "456"),
                    discovery = GlobalExistingBranchDiscoveryUiState(
                        repositories = listOf(
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = REPO_PATH,
                                pullRequestQuery = "456",
                                unsupportedPullRequestMessage = "Fork pull requests are not supported.",
                            ),
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = ENGINEERING_DOCS_PATH,
                                originBranches = listOf("feature/pr-search"),
                                originBranchRefreshSucceeded = true,
                                pullRequestQuery = "456",
                                pullRequest = pullRequestResult(
                                    repoRootPath = ENGINEERING_DOCS_PATH,
                                    number = 456,
                                    branch = "feature/pr-search",
                                    repositoryFullName = "owner/engineering-docs",
                                ),
                            ),
                        ),
                    ),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("PR #456 · owner/engineering-docs · feature/pr-search").assertIsDisplayed()
        onAllNodesWithText("Fork pull requests are not supported.").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalExistingBranchDialogConfirmsSelectedRepositoryBranch() = runComposeUiTest {
        val confirmations = mutableListOf<ExistingWorktreeResult>()
        setContent {
            var request by remember {
                mutableStateOf(PendingGlobalCreateWorktree(existingBranchQuery = "doc-search"))
            }
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = GlobalExistingBranchDiscoveryUiState(
                        repositories = listOf(
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = REPO_PATH,
                                branches = listOf("feature/other"),
                            ),
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = ENGINEERING_DOCS_PATH,
                                branches = listOf("feature/doc-search"),
                            ),
                        ),
                    ),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult) },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Branch · engineering-docs · feature/doc-search").performClick()
        onNodeWithText("Use Existing").performClick()

        assertEquals(
            listOf<ExistingWorktreeResult>(ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, "feature/doc-search")),
            confirmations,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalExistingBranchDialogRequiresSelectionForSameNamedBranches() = runComposeUiTest {
        val confirmations = mutableListOf<ExistingWorktreeResult>()
        val branch = "release/123"
        setContent {
            var request by remember {
                mutableStateOf(PendingGlobalCreateWorktree(existingBranchQuery = branch))
            }
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = GlobalExistingBranchDiscoveryUiState(
                        repositories = listOf(
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = ENGINEERING_DOCS_PATH,
                                branches = listOf(branch),
                            ),
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = REPO_PATH,
                                branches = listOf(branch),
                            ),
                        ),
                    ),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult) },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Branch · dev-lake-utils · release/123").assertIsDisplayed()
        onNodeWithText("Branch · engineering-docs · release/123").assertIsDisplayed()
        onNodeWithText("Use Existing").assertIsNotEnabled()

        onNodeWithText("Branch · engineering-docs · release/123").performClick()
        onNodeWithText("Use Existing").performClick()

        assertEquals(
            listOf<ExistingWorktreeResult>(ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch)),
            confirmations,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun selectionDoesNotConfirmUntilConfirmationButtonIsClicked() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        setContent {
            var request by remember { mutableStateOf(existingBranchRequest()) }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = loadedBranches(request.repoRootPath),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Branch · dev-lake-utils · feature/existing-worktree").performClick()
        assertTrue(confirmations.isEmpty())
        onNodeWithText("Use Existing").performClick()

        assertEquals(listOf("feature/existing-worktree"), confirmations)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterSelectsHighlightedResultBeforeConfirming() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        setContent {
            var request by remember { mutableStateOf(existingBranchRequest()) }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = loadedBranches(request.repoRootPath),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("existing-branch-search").performClick()
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }
        assertTrue(confirmations.isEmpty())
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("feature/existing-worktree"), confirmations)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterSelectsAndThenConfirmsLabeledPullRequestResult() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        setContent {
            var request by remember {
                mutableStateOf(existingBranchRequest().copy(existingBranchQuery = "123"))
            }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = request.repoRootPath,
                        branches = listOf("feature/pr-worktree"),
                        originBranches = listOf("feature/pr-worktree"),
                        originBranchRefreshSucceeded = true,
                        pullRequestQuery = "123",
                        pullRequest = ExistingPullRequestWorktreeResult(
                            repoRootPath = request.repoRootPath,
                            repositoryFullName = "owner/dev-lake-utils",
                            number = 123,
                            branch = "feature/pr-worktree",
                        ),
                    ),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("PR #123 · owner/dev-lake-utils · feature/pr-worktree").assertIsDisplayed()
        onNodeWithTag("existing-branch-search").performClick()
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }
        assertTrue(confirmations.isEmpty())
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("feature/pr-worktree"), confirmations)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterOnNewModeButtonChangesModeWithoutSelectingOrConfirmingBranch() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        val requestChanges = mutableListOf<PendingCreateWorktree>()
        setContent {
            var request by remember { mutableStateOf(existingBranchRequest()) }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = loadedBranches(request.repoRootPath),
                    onRequestChange = {
                        request = it
                        requestChanges += it
                    },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("new-worktree-mode").requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(CreateWorktreeMode.NEW, requestChanges.single().mode)
        assertEquals(null, requestChanges.single().selectedExistingResult)
        assertTrue(confirmations.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterOnCancelDismissesWithoutSelectingOrConfirmingBranch() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        val requestChanges = mutableListOf<PendingCreateWorktree>()
        var dismissals = 0
        setContent {
            var request by remember { mutableStateOf(existingBranchRequest()) }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = loadedBranches(request.repoRootPath),
                    onRequestChange = {
                        request = it
                        requestChanges += it
                    },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = { dismissals += 1 },
                )
            }
        }

        onNodeWithTag("cancel-existing-worktree").requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, dismissals)
        assertTrue(requestChanges.isEmpty())
        assertTrue(confirmations.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardCanSelectAndConfirmANonFirstResult() = runComposeUiTest {
        val confirmations = mutableListOf<String>()
        setContent {
            var request by remember {
                mutableStateOf(existingBranchRequest().copy(existingBranchQuery = "feature"))
            }
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = request.repoRootPath,
                        branches = listOf("feature/existing-worktree", "feature/other"),
                    ),
                    onRequestChange = { request = it },
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingResult).branch },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("existing-branch-search").performClick()
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("Branch · dev-lake-utils · feature/other").assertIsSelected()
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }
        assertTrue(confirmations.isEmpty())
        onNodeWithTag("existing-branch-search").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("feature/other"), confirmations)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun discoveryShowsLoadingIndicator() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = existingBranchRequest(),
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = REPO_PATH,
                        isLoading = true,
                    ),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithContentDescription("Loading existing branches").assertIsDisplayed()
    }

    private fun existingBranchRequest() = PendingCreateWorktree(
        repoRootPath = REPO_PATH,
        baseWorktreePath = REPO_PATH,
        baseBranch = "main",
        mode = CreateWorktreeMode.EXISTING,
        existingBranchQuery = "existing-worktree",
    )

    private fun loadedBranches(repoRootPath: String) = ExistingBranchDiscoveryUiState(
        repoRootPath = repoRootPath,
        branches = listOf("feature/existing-worktree"),
    )

    private fun pullRequestResult(
        number: Int,
        branch: String,
        repositoryFullName: String = "owner/dev-lake-utils",
        repoRootPath: String = REPO_PATH,
    ) = ExistingPullRequestWorktreeResult(
        repoRootPath = repoRootPath,
        repositoryFullName = repositoryFullName,
        number = number,
        branch = branch,
    )

    private companion object {
        const val REPO_PATH = "/repos/dev-lake-utils"
        const val ENGINEERING_DOCS_PATH = "/repos/engineering-docs"
    }
}
