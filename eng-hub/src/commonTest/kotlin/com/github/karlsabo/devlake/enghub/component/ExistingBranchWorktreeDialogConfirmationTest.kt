package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExistingBranchWorktreeDialogConfirmationTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun existingBranchDialogConfirmsCurrentResultWhenDiscoveryAddsWorktreePath() = runComposeUiTest {
        val branch = "feature/existing-worktree"
        val worktreePath = "/tmp/dev-lake-utils-existing-worktree"
        val confirmations = mutableListOf<ExistingWorktreeResult>()

        setContent {
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = existingBranchRequest(
                        selectedExistingResult = ExistingBranchWorktreeResult(REPO_PATH, branch),
                    ),
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = REPO_PATH,
                        branches = listOf(branch),
                        worktreePathsByBranch = mapOf(branch to worktreePath),
                    ),
                    onRequestChange = {},
                    onConfirm = { confirmations += it },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Use Existing").performClick()

        assertEquals(
            listOf<ExistingWorktreeResult>(ExistingBranchWorktreeResult(REPO_PATH, branch, worktreePath)),
            confirmations,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalExistingBranchDialogConfirmsCurrentResultWhenDiscoveryAddsWorktreePath() = runComposeUiTest {
        val branch = "feature/doc-search"
        val worktreePath = "/tmp/engineering-docs-doc-search"
        val confirmations = mutableListOf<ExistingWorktreeResult>()

        setContent {
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = PendingGlobalCreateWorktree(
                        existingBranchQuery = "doc-search",
                        selectedExistingResult = ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch),
                    ),
                    discovery = GlobalExistingBranchDiscoveryUiState(
                        repositories = listOf(
                            ExistingBranchDiscoveryUiState(
                                repoRootPath = ENGINEERING_DOCS_PATH,
                                branches = listOf(branch),
                                worktreePathsByBranch = mapOf(branch to worktreePath),
                            ),
                        ),
                    ),
                    onRequestChange = {},
                    onConfirm = { confirmations += it },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Use Existing").performClick()

        assertEquals(
            listOf<ExistingWorktreeResult>(ExistingBranchWorktreeResult(ENGINEERING_DOCS_PATH, branch, worktreePath)),
            confirmations,
        )
    }

    private fun existingBranchRequest(
        selectedExistingResult: ExistingWorktreeResult?,
    ) = PendingCreateWorktree(
        repoRootPath = REPO_PATH,
        baseWorktreePath = REPO_PATH,
        baseBranch = "main",
        mode = CreateWorktreeMode.EXISTING,
        existingBranchQuery = "existing-worktree",
        selectedExistingResult = selectedExistingResult,
    )

    private companion object {
        const val REPO_PATH = "/repos/dev-lake-utils"
        const val ENGINEERING_DOCS_PATH = "/repos/engineering-docs"
    }
}
