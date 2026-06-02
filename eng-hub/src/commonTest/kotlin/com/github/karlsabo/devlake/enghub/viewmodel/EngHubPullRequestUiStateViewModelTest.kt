package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubPullRequestUiStateViewModelTest {

    @Test
    fun usesPullRequestApiUrlForPullRequestDetailFetches() = runBlocking {
        val issueApiUrl = "https://api.github.com/repos/test-org/test-repo/issues/25843"
        val pullApiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/25843"
        val api = RecordingGitHubApi(
            pullRequestsByUrl = mapOf(
                issueApiUrl to PullRequest(url = issueApiUrl),
                pullApiUrl to PullRequest(
                    url = pullApiUrl,
                    head = PullRequestHead(ref = "feature/pr-url", sha = "abc123"),
                ),
            ),
        )

        val uiState = api.buildPullRequestUiStates(listOf(testIssue(issueApiUrl, pullApiUrl))).single()

        assertEquals(listOf(pullApiUrl), api.pullRequestByUrlCalls)
        assertEquals("feature/pr-url", uiState.headRef)
        assertEquals("3/3 passed", uiState.ciSummaryText)
        assertEquals(pullApiUrl, uiState.apiUrl)
    }

    @Test
    fun fallsBackToIssueApiUrlWhenPullRequestApiUrlIsAbsent() = runBlocking {
        val issueApiUrl = "https://api.github.com/repos/test-org/test-repo/issues/25843"
        val api = RecordingGitHubApi(
            pullRequestsByUrl = mapOf(
                issueApiUrl to PullRequest(
                    url = issueApiUrl,
                    head = PullRequestHead(ref = "feature/fallback", sha = "def456"),
                ),
            ),
        )

        val uiState = api.buildPullRequestUiStates(listOf(testIssue(issueApiUrl, pullApiUrl = null))).single()

        assertEquals(listOf(issueApiUrl), api.pullRequestByUrlCalls)
        assertEquals("feature/fallback", uiState.headRef)
        assertEquals("3/3 passed", uiState.ciSummaryText)
        assertEquals(issueApiUrl, uiState.apiUrl)
    }
}
