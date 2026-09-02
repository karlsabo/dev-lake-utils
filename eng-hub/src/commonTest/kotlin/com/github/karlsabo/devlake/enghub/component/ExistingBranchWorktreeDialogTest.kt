package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingBranch) },
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
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingBranch) },
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
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingBranch) },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("new-worktree-mode").requestFocus().performKeyInput { pressKey(Key.Enter) }

        assertEquals(CreateWorktreeMode.NEW, requestChanges.single().mode)
        assertEquals(null, requestChanges.single().selectedExistingBranch)
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
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingBranch) },
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
                    onConfirm = { confirmations += requireNotNull(request.selectedExistingBranch) },
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

    private companion object {
        const val REPO_PATH = "/repos/dev-lake-utils"
    }
}
