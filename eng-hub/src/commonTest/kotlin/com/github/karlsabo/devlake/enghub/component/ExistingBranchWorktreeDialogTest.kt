package com.github.karlsabo.devlake.enghub.component

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
