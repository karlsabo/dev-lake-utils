package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualifiedGlobalBranchDialogTest {
    @Test
    fun qualifiedSearchNarrowsByGitHubOriginAndPreservesBranchSlashes() {
        val branch = "feature/shared/nested"
        val discovery = qualifiedBranchDiscovery(branch)

        assertEquals(
            listOf(ExistingBranchWorktreeResult(DEV_LAKE_PATH, branch)),
            globalExistingWorktreeResults(discovery, "owner/dev-lake-utils:$branch"),
        )
    }

    @Test
    fun qualifiedSearchDoesNotUseLocalDirectoryNameAsRepositoryIdentity() {
        val branch = "feature/shared"
        val discovery = GlobalExistingBranchDiscoveryUiState(
            repositories = listOf(
                ExistingBranchDiscoveryUiState(
                    repoRootPath = DEV_LAKE_PATH,
                    branches = listOf(branch),
                ),
            ),
        )

        assertTrue(globalExistingWorktreeResults(discovery, "owner/dev-lake-utils:$branch").isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalDialogShowsOnlyOriginMatchedBranchForQualifiedInput() = runComposeUiTest {
        val branch = "feature/shared"
        setContent {
            MaterialTheme {
                GlobalExistingBranchWorktreeDialogContent(
                    request = PendingGlobalCreateWorktree(
                        existingBranchQuery = "owner/dev-lake-utils:$branch",
                    ),
                    discovery = qualifiedBranchDiscovery(branch),
                    onRequestChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Branch · dev-lake-utils · $branch").assertIsDisplayed()
        onAllNodesWithText("Branch · engineering-docs · $branch").assertCountEquals(0)
    }
}

private fun qualifiedBranchDiscovery(branch: String) = GlobalExistingBranchDiscoveryUiState(
    repositories = listOf(
        ExistingBranchDiscoveryUiState(
            repoRootPath = DEV_LAKE_PATH,
            repositoryIdentity = GitHubRepositoryIdentity("Owner", "Dev-Lake-Utils"),
            branches = listOf(branch),
        ),
        ExistingBranchDiscoveryUiState(
            repoRootPath = ENGINEERING_DOCS_PATH,
            repositoryIdentity = GitHubRepositoryIdentity("owner", "engineering-docs"),
            branches = listOf(branch),
        ),
    ),
)

private const val DEV_LAKE_PATH = "/repos/dev-lake-utils"
private const val ENGINEERING_DOCS_PATH = "/repos/engineering-docs"
