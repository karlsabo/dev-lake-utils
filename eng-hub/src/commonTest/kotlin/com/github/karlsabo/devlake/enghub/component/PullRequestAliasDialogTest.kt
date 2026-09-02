package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class PullRequestAliasDialogTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun qualifiedPullRequestUrlRendersTheResolvedPullRequest() = runComposeUiTest {
        val query = "https://github.com/owner/dev-lake-utils/pull/123"
        setContent {
            MaterialTheme {
                ExistingBranchWorktreeDialogContent(
                    request = PendingCreateWorktree(
                        repoRootPath = REPO_PATH,
                        baseWorktreePath = REPO_PATH,
                        baseBranch = "main",
                        mode = CreateWorktreeMode.EXISTING,
                        existingBranchQuery = query,
                    ),
                    discovery = ExistingBranchDiscoveryUiState(
                        repoRootPath = REPO_PATH,
                        originBranches = listOf(PR_BRANCH),
                        originBranchRefreshSucceeded = true,
                        pullRequestQuery = query,
                        pullRequest = ExistingPullRequestWorktreeResult(
                            repoRootPath = REPO_PATH,
                            repositoryFullName = "owner/dev-lake-utils",
                            number = 123,
                            branch = PR_BRANCH,
                        ),
                    ),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("PR #123 · owner/dev-lake-utils · $PR_BRANCH").assertIsDisplayed()
    }

    private companion object {
        const val REPO_PATH = "/repos/dev-lake-utils"
        const val PR_BRANCH = "feature/pr-123"
    }
}
