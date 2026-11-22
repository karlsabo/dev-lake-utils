package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.PullRequestProvider
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.gitlab.GitLabMergeRequest
import com.github.karlsabo.gitlab.GitLabMergeRequestApi
import com.github.karlsabo.gitlab.GitLabMergeRequestReferences
import com.github.karlsabo.gitlab.GitLabUser
import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GitLabPullRequestPollingTest {
    private val apiConfig = GitLabApiRestConfig("https://gitlab.internal.example", "glpat-synthetic")

    @Test
    fun authoredMergeRequestIsListedAsGitLab() = runTest {
        val api = FakeGitLabApi(listOf(mergeRequest()))

        val result = gitLabPullRequestsForConfig(EngHubConfig(), { api }, apiConfig).first()

        val uiState = result.getOrThrow().single()
        assertEquals(PullRequestProvider.GitLab, uiState.provider)
        assertEquals("!42", uiState.referenceLabel)
        assertEquals(42, uiState.number)
        assertEquals("Add atlas feature", uiState.title)
        assertEquals("games/atlas", uiState.repositoryFullName)
        assertEquals("https://gitlab.internal.example/games/atlas/-/merge_requests/42", uiState.htmlUrl)
        assertNull(uiState.headRef)
        assertTrue(api.requestedAuthoredMergeRequests)
    }

    @Test
    fun authoredMergeRequestFailureIsReportedAsAFailedPoll() = runTest {
        val api = FakeGitLabApi(emptyList(), failure = IllegalStateException("instance unavailable"))

        val result = gitLabPullRequestsForConfig(EngHubConfig(), { api }, apiConfig).first()

        assertEquals("instance unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun failureAfterASuccessfulPollIsStillReported() = runBlocking {
        var polls = 0
        val api = object : GitLabMergeRequestApi {
            override suspend fun getAuthenticatedUser(): GitLabUser = GitLabUser(id = 1, username = "atlas-user")

            override suspend fun getAuthoredOpenMergeRequests(): List<GitLabMergeRequest> {
                polls++
                if (polls > 1) error("token expired")
                return listOf(mergeRequest())
            }
        }

        val results = withTimeout(10_000.milliseconds) {
            gitLabPullRequestsForConfig(EngHubConfig(pollIntervalMs = 1), { api }, apiConfig).take(2).toList()
        }

        assertEquals(listOf("!42"), results[0].getOrThrow().map { it.referenceLabel })
        assertEquals("token expired", results[1].exceptionOrNull()?.message)
        assertEquals(2, polls)
    }

    @Test
    fun githubAndGitLabResultsCoexistWithDistinctProviderLabels() {
        val github = gitHubPullRequest()
        val gitLab = mergeRequest().toPullRequestUiState()

        val merged = mergePullRequestResults(Result.success(listOf(github)), Result.success(listOf(gitLab)))

        assertEquals(listOf("GitHub", "GitLab"), merged?.pullRequests.orEmpty().map { it.provider.label })
        assertEquals(listOf("#7", "!42"), merged?.pullRequests.orEmpty().map { it.referenceLabel })
        assertEquals(emptyList(), merged?.failures)
    }

    @Test
    fun oneProviderFailureRemainsObservableNextToTheOtherProvidersItems() {
        val gitLab = mergeRequest().toPullRequestUiState()

        val merged = mergePullRequestResults(
            Result.failure(IllegalStateException("GitHub unavailable")),
            Result.success(listOf(gitLab)),
        )

        assertEquals(listOf("!42"), merged?.pullRequests.orEmpty().map { it.referenceLabel })
        assertEquals(listOf(PullRequestProvider.GitHub), merged?.failures.orEmpty().map { it.provider })
        assertEquals(listOf("GitHub unavailable"), merged?.failures.orEmpty().map { it.message })
    }

    @Test
    fun bothProvidersUnavailableShowsNoProviderPaneData() {
        assertNull(mergePullRequestResults(null, null))
    }

    private fun mergeRequest() = GitLabMergeRequest(
        iid = 42,
        title = "Add atlas feature",
        webUrl = "https://gitlab.internal.example/games/atlas/-/merge_requests/42",
        sourceBranch = "feature/atlas",
        references = GitLabMergeRequestReferences(full = "games/atlas!42"),
    )

    private fun gitHubPullRequest() = PullRequestUiState(
        number = 7,
        title = "Add hub feature",
        htmlUrl = "https://github.com/games/atlas/pull/7",
        repositoryFullName = "games/atlas",
        owner = "games",
        repo = "atlas",
        isDraft = false,
        ciStatus = CiStatus.PENDING,
        ciSummaryText = "0/0 passed",
        approvedCount = 0,
        requestedCount = 0,
        reviewSummaryText = "0/0 approved",
        headRef = "feature/hub",
        apiUrl = "https://api.github.com/repos/games/atlas/pulls/7",
    )
}

private class FakeGitLabApi(
    private val mergeRequests: List<GitLabMergeRequest>,
    private val failure: Throwable? = null,
) : GitLabMergeRequestApi {
    var requestedAuthoredMergeRequests = false

    override suspend fun getAuthenticatedUser(): GitLabUser = GitLabUser(id = 1, username = "atlas-user")

    override suspend fun getAuthoredOpenMergeRequests(): List<GitLabMergeRequest> {
        requestedAuthoredMergeRequests = true
        failure?.let { throw it }
        return mergeRequests
    }
}
