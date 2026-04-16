package com.github.karlsabo.devlake.metrics.ui

import com.github.karlsabo.devlake.metrics.UserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.UserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.UserMetricPublisherState
import com.github.karlsabo.devlake.metrics.UserMetricsBuilder
import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.ProjectMilestone
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UserMetricPublisherDependenciesTest {

    @Test
    fun loadMetricsPreviewUsesProvidedDependencies() = runBlocking {
        val user = User(
            id = "user-1",
            email = "user-1@example.com",
            name = "User One",
            gitHubId = "user-one",
        )
        val config = UserMetricPublisherConfig(
            organizationIds = listOf("test-org"),
            userIds = listOf(user.id),
            metricInformationPostfix = "Configured postfix",
        )
        val fakeProjectManagementApi = NoOpProjectManagementApi()
        val fakeGitHubApi = NoOpGitHubApi()
        val expectedMetrics = UserMetrics(
            userId = user.id,
            email = user.email!!,
            pullRequestsPastWeek = emptyList(),
            pullRequestsYearToDateCount = 5u,
            prReviewCountYtd = 3u,
            issuesClosedLastWeek = emptyList(),
            issuesClosedYearToDateCount = 8u,
        )
        val recordingBuilder = RecordingMetricsBuilder(expectedMetrics)
        val dependencies = UserMetricPublisherDependencies(
            usersConfig = UsersConfig(listOf(user)),
            projectManagementApi = fakeProjectManagementApi,
            gitHubApi = fakeGitHubApi,
            metricsBuilder = recordingBuilder,
        )
        val state = UserMetricPublisherState()

        loadConfiguration(
            state = state,
            loadConfig = { config },
            dependencyProvider = { providedConfig ->
                assertEquals(config, providedConfig)
                dependencies
            },
        )
        loadMetrics(state)

        assertEquals(config, state.config)
        assertSame(dependencies, state.dependencies)
        assertEquals(listOf(expectedMetrics), state.metrics)
        assertEquals(listOf(user.id), recordingBuilder.requestedUserIds)
        assertEquals(listOf(config.organizationIds), recordingBuilder.requestedOrganizationIds)
        assertSame(fakeProjectManagementApi, recordingBuilder.projectManagementApi)
        assertSame(fakeGitHubApi, recordingBuilder.gitHubApi)
        assertEquals(
            buildString {
                appendLine()
                appendLine(user.id)
                append(expectedMetrics.toSlackMarkdown())
                appendLine()
                append(config.metricInformationPostfix)
            },
            state.metricsPreviewText,
        )
    }
}

private class RecordingMetricsBuilder(
    private val metrics: UserMetrics,
) : UserMetricsBuilder {
    val requestedUserIds = mutableListOf<String>()
    val requestedOrganizationIds = mutableListOf<List<String>>()
    var projectManagementApi: ProjectManagementApi? = null
        private set
    var gitHubApi: GitHubApi? = null
        private set

    override suspend fun createUserMetrics(
        user: User,
        organizationIds: List<String>,
        projectManagementApi: ProjectManagementApi,
        gitHubApi: GitHubApi,
    ): UserMetrics {
        requestedUserIds += user.id
        requestedOrganizationIds += organizationIds
        this.projectManagementApi = projectManagementApi
        this.gitHubApi = gitHubApi
        return metrics
    }
}

private class NoOpProjectManagementApi : ProjectManagementApi {
    override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> = emptyList()

    override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> = emptyList()

    override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> = emptyList()

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> = emptyList()

    override suspend fun getIssuesResolved(user: User, startDate: Instant, endDate: Instant): List<ProjectIssue> =
        emptyList()

    override suspend fun getIssuesResolvedCount(user: User, startDate: Instant, endDate: Instant): UInt = 0u

    override suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> = emptyList()

    override suspend fun getMilestones(projectId: String): List<ProjectMilestone> = emptyList()

    override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> = emptyList()
}

private class NoOpGitHubApi : GitHubApi {
    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt = 0u

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt = 0u

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> = emptyList()

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> = emptyList()

    override suspend fun listNotifications(): List<Notification> = emptyList()

    override suspend fun getPullRequestByUrl(url: String): PullRequest = PullRequest(url = url)

    override suspend fun approvePullRequestByUrl(url: String, body: String?) = Unit

    override suspend fun markNotificationAsDone(threadId: String) = Unit

    override suspend fun unsubscribeFromNotification(threadId: String) = Unit

    override suspend fun hasAnyApprovedReview(url: String): Boolean = false

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> =
        emptyList()

    override suspend fun getCheckRunsForRef(owner: String, repo: String, ref: String): CheckRunSummary {
        return CheckRunSummary(total = 0, passed = 0, failed = 0, inProgress = 0, status = CiStatus.PENDING)
    }

    override suspend fun getReviewSummary(owner: String, repo: String, prNumber: Int): ReviewSummary {
        return ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())
    }

    override suspend fun submitReview(prApiUrl: String, event: ReviewStateValue, reviewComment: String?) = Unit
}
