package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Label
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.User
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubViewModelTest {

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

private class RecordingGitHubApi(
    private val pullRequestsByUrl: Map<String, PullRequest>,
) : GitHubApi {
    val pullRequestByUrlCalls = mutableListOf<String>()

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        pullRequestByUrlCalls += url
        return pullRequestsByUrl.getValue(url)
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun getCheckRunsForRef(owner: String, repo: String, ref: String): CheckRunSummary {
        return CheckRunSummary(total = 3, passed = 3, failed = 0, inProgress = 0, status = CiStatus.PASSED)
    }

    override suspend fun getReviewSummary(owner: String, repo: String, prNumber: Int): ReviewSummary {
        return ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())
    }

    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun listNotifications(): List<Notification> {
        error("Unexpected call")
    }

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        error("Unexpected call")
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        error("Unexpected call")
    }

    override suspend fun submitReview(prApiUrl: String, event: ReviewStateValue, reviewComment: String?) {
        error("Unexpected call")
    }
}

private fun testIssue(issueApiUrl: String, pullApiUrl: String?): Issue {
    val now = Clock.System.now()
    return Issue(
        url = issueApiUrl,
        repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
        id = 25843L,
        number = 25843,
        state = "open",
        title = "Use the PR API URL",
        user = User(
            login = "test-user",
            id = 1L,
            avatarUrl = null,
            url = "https://api.github.com/users/test-user",
            htmlUrl = "https://github.com/test-user",
        ),
        body = null,
        htmlUrl = "https://github.com/test-org/test-repo/pull/25843",
        labels = emptyList<Label>(),
        draft = false,
        createdAt = now,
        updatedAt = now,
        closedAt = null,
        pullRequest = pullApiUrl?.let { PullRequest(url = it) },
        comments = 0,
    )
}
