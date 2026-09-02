package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.PullRequestRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PullRequestAliasDiscoveryTest {
    @Test
    fun repositoryDiscoveryResolvesEverySupportedAlias() = runBlocking {
        val gitHub = pullRequestApi()
        val viewModel = aliasViewModel(gitHub)
        val aliases = listOf(
            "123",
            "#123",
            "owner/dev-lake-utils#123",
            "https://github.com/owner/dev-lake-utils/pull/123",
        )

        aliases.forEach { alias ->
            viewModel.discoverExistingPullRequest(DEV_LAKE_ROOT, alias)
            val discovery = withTimeout(5.seconds) {
                viewModel.existingBranchDiscoveryStateFlow.first {
                    it.pullRequestQuery == alias && !it.isPullRequestLoading
                }
            }
            assertEquals("owner/dev-lake-utils", discovery.pullRequest?.repositoryFullName, alias)
            assertEquals(123, discovery.pullRequest?.number, alias)
            assertEquals(PR_BRANCH, discovery.pullRequest?.branch, alias)
        }
        assertEquals(List(aliases.size) { PULL_REQUEST_API_URL }, gitHub.pullRequestByUrlCalls)
    }

    @Test
    fun repositoryDiscoveryRejectsQualifiedAliasForAnotherRepository() = runBlocking {
        val gitHub = pullRequestApi()
        val viewModel = aliasViewModel(gitHub)
        val query = "owner/engineering-docs#123"

        viewModel.discoverExistingPullRequest(DEV_LAKE_ROOT, query)

        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first {
                it.pullRequestQuery == query && !it.isPullRequestLoading
            }
        }
        assertNull(discovery.pullRequest)
        assertTrue(gitHub.pullRequestByUrlCalls.isEmpty())
    }

    private fun aliasViewModel(gitHub: RecordingGitHubApi) = createLocalRepositoryViewModel(
        gitWorktreeApi = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originUrlsByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "git@github.com:owner/dev-lake-utils.git",
                ),
            ),
        ),
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
        services = LocalRepositoryViewModelServices(gitHubApi = gitHub),
    )

    private fun pullRequestApi() = RecordingGitHubApi(
        pullRequestsByUrl = mapOf(
            PULL_REQUEST_API_URL to PullRequest(
                number = 123,
                head = PullRequestHead(
                    ref = PR_BRANCH,
                    repo = PullRequestRepository("owner/dev-lake-utils"),
                ),
            ),
        ),
    )

    private companion object {
        const val PR_BRANCH = "feature/pr-worktree"
        const val PULL_REQUEST_API_URL = "https://api.github.com/repos/owner/dev-lake-utils/pulls/123"
    }
}
