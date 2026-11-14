package com.github.karlsabo.devlake.enghub.component

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
