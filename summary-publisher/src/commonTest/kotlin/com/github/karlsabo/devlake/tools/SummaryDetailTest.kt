package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Label
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.jira.Comment
import com.github.karlsabo.jira.CommentBody
import com.github.karlsabo.jira.ContentNode
import com.github.karlsabo.jira.JiraApi
import com.github.karlsabo.jira.JiraAvatarUrls
import com.github.karlsabo.jira.JiraUser
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyIncident
import com.github.karlsabo.text.TextSummarizerFake
import com.github.karlsabo.tools.createSummary
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import com.github.karlsabo.github.Issue as GitHubIssue
import com.github.karlsabo.github.User as GitHubUser
import com.github.karlsabo.jira.Issue as JiraIssue

/**
 * Test for the summary creation functionality using mock services.
 * This test doesn't connect to any real external services like Jira, GitHub, OpenAI, or PagerDuty.
 */
class SummaryDetailTest {

    @Test
    fun testCreateSummary() = runBlocking {
        val jiraApi = JiraApiMock()
        val gitHubApi = GitHubApiMock()
        val pagerDutyApi = PagerDutyApiMock()
        val textSummarizer = TextSummarizerFake()

        val gitHubOrganizationIds = listOf("test-org")
        val pagerDutyServiceIds = listOf("PD123")
        val projects = listOf(
            Project(
                id = 1L,
                title = "Test Project",
                topLevelIssueKeys = listOf("TEST-1"),
                isVerboseMilestones = true
            )
        )
        val users = listOf(
            User(
                id = "user1",
                name = "Test User",
                email = "test@example.local",
                jiraId = "jira-user-1",
                gitHubId = "github-user-1"
            )
        )
        val miscUsers = listOf(
            User(
                id = "misc-user1",
                name = "Misc User",
                email = "misc@example.local",
                jiraId = "jira-misc-1",
                gitHubId = "github-misc-1"
            )
        )
        val summaryName = "Test Summary"
        val isMiscellaneousProjectIncluded = true

        val summary = createSummary(
            jiraApi,
            gitHubApi,
            gitHubOrganizationIds,
            pagerDutyApi,
            pagerDutyServiceIds,
            textSummarizer,
            projects,
            7.days,
            users,
            miscUsers,
            summaryName,
            isMiscellaneousProjectIncluded
        )

        assertNotNull(summary)
        assertEquals(summaryName, summary.summaryName)
        assertTrue(summary.projectSummaries.isNotEmpty())

        println("Summary:")
        println(summary.toSlackMarkup())

        summary.projectSummaries.forEach { projectSummary ->
            println("Project: ${projectSummary.project.title}")
            assertNotNull(projectSummary.durationProgressSummary)
        }

        assertNotNull(summary.pagerDutyAlerts)
        assertTrue(summary.pagerDutyAlerts!!.isNotEmpty())
    }

    private class JiraApiMock : JiraApi {
        private val now = Clock.System.now()
        private val oneWeekAgo = now.minus(7.days)

        private val mockIssues = mapOf(
            "TEST-1" to JiraIssue(
                id = "1",
                url = "https://jira.example.local/browse/TEST-1",
                iconUrl = "https://jira.example.local/images/epic.png",
                issueKey = "TEST-1",
                title = "Test Epic",
                description = "This is a test epic",
                type = "Epic",
                status = "In Progress",
                resolutionDate = null,
                createdDate = oneWeekAgo,
                assigneeId = "jira-user-1",
                assigneeName = "Test User"
            ),
            "TEST-2" to JiraIssue(
                id = "2",
                url = "https://jira.example.local/browse/TEST-2",
                iconUrl = "https://jira.example.local/images/story.png",
                issueKey = "TEST-2",
                title = "Test Story",
                description = "This is a test story",
                type = "Story",
                status = "Done",
                resolutionDate = now.minus(1.days),
                createdDate = oneWeekAgo.plus(1.days),
                parentIssueId = "1",
                assigneeId = "jira-user-1",
                assigneeName = "Test User"
            ),
            "TEST-3" to JiraIssue(
                id = "3",
                url = "https://jira.example.local/browse/TEST-3",
                iconUrl = "https://jira.example.local/images/bug.png",
                issueKey = "TEST-3",
                title = "Test Bug",
                description = "This is a test bug",
                type = "Bug",
                status = "Done",
                resolutionDate = now.minus(2.days),
                createdDate = oneWeekAgo.plus(2.days),
                parentIssueId = "1",
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User"
            )
        )

        override suspend fun runJql(jql: String): List<JiraIssue> {
            // For simplicity, return child issues of TEST-1 for any JQL that mentions parent
            return if (jql.contains("parent")) {
                mockIssues.values.filter { it.parentIssueId == "1" }.toList()
            } else {
                // Return resolved issues for any other JQL
                mockIssues.values.filter { it.resolutionDate != null }.toList()
            }
        }

        override suspend fun getIssues(issueKeys: List<String>): List<JiraIssue> {
            return issueKeys.mapNotNull { mockIssues[it] }
        }

        override suspend fun getChildIssues(issueKeys: List<String>): List<JiraIssue> {
            val parentIds = issueKeys.mapNotNull { key -> mockIssues[key]?.id }
            return mockIssues.values.filter { it.parentIssueId in parentIds }.toList()
        }

        override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment> {
            // Create mock comments for the issue
            val avatarUrls = JiraAvatarUrls(
                size48x48 = "https://jira.example.local/avatar/48.png",
                size24x24 = "https://jira.example.local/avatar/24.png",
                size16x16 = "https://jira.example.local/avatar/16.png",
                size32x32 = "https://jira.example.local/avatar/32.png"
            )

            val jiraUser = JiraUser(
                self = "https://jira.example.local/user/test",
                accountId = "jira-user-1",
                emailAddress = "test@example.local",
                avatarUrls = avatarUrls,
                displayName = "Test User",
                active = true,
                timeZone = "UTC",
                accountType = "atlassian"
            )

            val commentBody = CommentBody(
                type = "doc",
                version = 1,
                content = listOf(
                    ContentNode(
                        type = "paragraph",
                        content = listOf(
                            ContentNode(
                                type = "text",
                                text = "This is a test comment for $issueKey"
                            )
                        )
                    )
                )
            )

            return List(maxResults.coerceAtMost(3)) { index ->
                Comment(
                    self = "https://jira.example.local/comment/$index",
                    id = "$index",
                    author = jiraUser,
                    body = commentBody,
                    updateAuthor = jiraUser,
                    created = now.minus((index + 1).days),
                    updated = now.minus((index + 1).days)
                )
            }
        }

        override suspend fun getIssuesResolved(
            userJiraId: String,
            startDate: Instant,
            endDate: Instant,
        ): List<JiraIssue> {
            return mockIssues.values.filter { issue ->
                issue.assigneeId == userJiraId &&
                        issue.resolutionDate?.let { date ->
                            date >= startDate && date <= endDate
                        } ?: false
            }.toList()
        }

        override suspend fun getIssuesResolvedCount(
            userJiraId: String,
            startDate: Instant,
            endDate: Instant,
        ): UInt {
            return getIssuesResolved(userJiraId, startDate, endDate).size.toUInt()
        }
    }

    /**
     * Mock implementation of GitHubApi for testing.
     */
    private class GitHubApiMock : GitHubApi {
        private val now = Clock.System.now()
        private val oneWeekAgo = now.minus(7.days)

        private val mockUser = GitHubUser(
            login = "github-user-1",
            id = 12345,
            avatarUrl = "https://github.com/avatar.png",
            url = "https://api.github.com/users/github-user-1",
            htmlUrl = "https://github.com/github-user-1"
        )

        private val mockPullRequests = listOf(
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/test-repo/issues/1",
                repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
                id = 1L,
                number = 1,
                state = "closed",
                title = "Test PR 1",
                user = mockUser,
                body = "This is a test PR that fixes TEST-2",
                htmlUrl = "https://github.com/test-org/test-repo/pull/1",
                labels = listOf(Label(name = "bug", id = 1L, color = "red", description = "Bug fix")),
                draft = false,
                createdAt = oneWeekAgo.plus(2.days),
                updatedAt = now.minus(1.days),
                closedAt = now.minus(1.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/test-repo/pulls/1",
                    htmlUrl = "https://github.com/test-org/test-repo/pull/1",
                    mergedAt = now.minus(1.days)
                ),
                comments = 2
            ),
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/test-repo/issues/2",
                repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
                id = 2L,
                number = 2,
                state = "closed",
                title = "Test PR 2",
                user = mockUser,
                body = "This is a test PR that fixes TEST-3",
                htmlUrl = "https://github.com/test-org/test-repo/pull/2",
                labels = listOf(Label(name = "enhancement", id = 2L, color = "green", description = "Enhancement")),
                draft = false,
                createdAt = oneWeekAgo.plus(3.days),
                updatedAt = now.minus(2.days),
                closedAt = now.minus(2.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/test-repo/pulls/2",
                    htmlUrl = "https://github.com/test-org/test-repo/pull/2",
                    mergedAt = now.minus(2.days)
                ),
                comments = 1
            )
        )

        override suspend fun getMergedPullRequestCount(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: Instant,
            endDate: Instant,
        ): UInt {
            return mockPullRequests.count { pr ->
                pr.user.login == gitHubUserId &&
                        pr.pullRequest?.mergedAt?.let { mergedAt ->
                            mergedAt >= startDate && mergedAt <= endDate
                        } ?: false
            }.toUInt()
        }

        override suspend fun getMergedPullRequests(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: Instant,
            endDate: Instant,
        ): List<GitHubIssue> {
            return mockPullRequests.filter { pr ->
                pr.user.login == gitHubUserId &&
                        pr.pullRequest?.mergedAt?.let { mergedAt ->
                            mergedAt >= startDate && mergedAt <= endDate
                        } ?: false
            }
        }

        override suspend fun searchPullRequestsByText(
            searchText: String,
            organizationIds: List<String>,
            startDateInclusive: Instant,
            endDateInclusive: Instant,
        ): List<GitHubIssue> {
            return mockPullRequests.filter { pr ->
                (pr.title.contains(searchText) || pr.body?.contains(searchText) == true) &&
                        pr.pullRequest?.mergedAt?.let { mergedAt ->
                            mergedAt >= startDateInclusive && mergedAt <= endDateInclusive
                        } ?: false
            }
        }
    }

    /**
     * Mock implementation of PagerDutyApi for testing.
     */
    private class PagerDutyApiMock : PagerDutyApi {
        private val now = Clock.System.now()
        private val oneWeekAgo = now.minus(7.days)

        private val mockIncidents = listOf(
            PagerDutyIncident(
                id = "INCIDENT1",
                incidentNumber = 1,
                title = "Test Incident 1",
                description = "This is a test incident",
                status = "resolved",
                urgency = "high",
                serviceId = "PD123",
                serviceName = "Test Service",
                createdAt = oneWeekAgo.plus(1.days),
                lastStatusChangeAt = now.minus(3.days),
                resolvedAt = now.minus(3.days),
                htmlUrl = "https://pagerduty.example.local/incidents/1"
            ),
            PagerDutyIncident(
                id = "INCIDENT2",
                incidentNumber = 2,
                title = "Test Incident 2",
                description = "This is another test incident",
                status = "resolved",
                urgency = "low",
                serviceId = "PD123",
                serviceName = "Test Service",
                createdAt = oneWeekAgo.plus(3.days),
                lastStatusChangeAt = now.minus(1.days),
                resolvedAt = now.minus(1.days),
                htmlUrl = "https://pagerduty.example.local/incidents/2"
            )
        )

        override suspend fun getServicePages(
            serviceId: String,
            startTimeInclusive: Instant,
            endTimeExclusive: Instant,
        ): List<PagerDutyIncident> {
            return mockIncidents.filter {
                it.serviceId == serviceId &&
                        it.createdAt >= startTimeInclusive &&
                        it.createdAt < endTimeExclusive
            }
        }
    }
}
