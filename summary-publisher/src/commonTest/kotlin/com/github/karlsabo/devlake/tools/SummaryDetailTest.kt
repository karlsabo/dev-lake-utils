package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Label
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyIncident
import com.github.karlsabo.pagerduty.Service
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.ProjectMilestone
import com.github.karlsabo.projectmanagement.StatusCategory
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
import kotlin.time.Duration.Companion.minutes
import com.github.karlsabo.github.Issue as GitHubIssue
import com.github.karlsabo.github.User as GitHubUser

/**
 * Test for the summary creation functionality using mock services.
 * This test doesn't connect to any real external services like Jira, GitHub, OpenAI, or PagerDuty.
 */
class SummaryDetailTest {

    @Test
    fun testCreateSummary() = runBlocking {
        val projectManagementApi = ProjectManagementApiMock()
        val gitHubApi = GitHubApiMock()
        val pagerDutyApi = PagerDutyApiMock()
        val textSummarizer = TextSummarizerFake()

        val gitHubOrganizationIds = listOf("test-org")
        val pagerDutyServiceIds = listOf("PD123")
        val projects = listOf(
            Project(
                id = 1L,
                title = "Test Project 1",
                topLevelIssueKeys = listOf("TEST-1", "TEST-4"),
                isVerboseMilestones = true
            ),
            Project(
                id = 2L,
                title = "Test Project 2",
                topLevelIssueKeys = listOf("PROJ2-1", "PROJ2-2"),
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
            projectManagementApi,
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

    private class ProjectManagementApiMock : ProjectManagementApi {
        private val now = Clock.System.now()
        private val oneWeekAgo = now.minus(7.days)

        private val mockIssues = mapOf(
            // Test Project 1 - Epic 1 (In Progress)
            "TEST-1" to ProjectIssue(
                id = "1",
                key = "TEST-1",
                url = "https://jira.example.local/browse/TEST-1",
                title = "Test Epic 1",
                description = "This is a test epic that is in progress",
                issueType = "Epic",
                status = "In Progress",
                statusCategory = StatusCategory.IN_PROGRESS,
                completedAt = null,
                createdAt = oneWeekAgo,
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = null
            ),
            "TEST-2" to ProjectIssue(
                id = "2",
                key = "TEST-2",
                url = "https://jira.example.local/browse/TEST-2",
                title = "Test Story 1",
                description = "This is a test story for Epic 1",
                issueType = "Story",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(1.days),
                createdAt = oneWeekAgo.plus(1.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "TEST-1"
            ),
            "TEST-3" to ProjectIssue(
                id = "3",
                key = "TEST-3",
                url = "https://jira.example.local/browse/TEST-3",
                title = "Test Bug 1",
                description = "This is a test bug for Epic 1",
                issueType = "Bug",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(2.days),
                createdAt = oneWeekAgo.plus(2.days),
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User",
                parentKey = "TEST-1"
            ),
            "TEST-5" to ProjectIssue(
                id = "5",
                key = "TEST-5",
                url = "https://jira.example.local/browse/TEST-5",
                title = "Test Task 1",
                description = "This is a test task for Epic 1 that is still in progress",
                issueType = "Task",
                status = "In Progress",
                statusCategory = StatusCategory.IN_PROGRESS,
                completedAt = null,
                createdAt = oneWeekAgo.plus(3.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "TEST-1"
            ),

            // Test Project 1 - Epic 2 (Completed)
            "TEST-4" to ProjectIssue(
                id = "4",
                key = "TEST-4",
                url = "https://jira.example.local/browse/TEST-4",
                title = "Test Epic 2",
                description = "This is a completed test epic",
                issueType = "Epic",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(1.days),
                createdAt = oneWeekAgo.plus(1.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = null
            ),
            "TEST-6" to ProjectIssue(
                id = "6",
                key = "TEST-6",
                url = "https://jira.example.local/browse/TEST-6",
                title = "Test Story 2",
                description = "This is a test story for Epic 2",
                issueType = "Story",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(2.days),
                createdAt = oneWeekAgo.plus(2.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "TEST-4"
            ),
            "TEST-7" to ProjectIssue(
                id = "7",
                key = "TEST-7",
                url = "https://jira.example.local/browse/TEST-7",
                title = "Test Bug 2",
                description = "This is a test bug for Epic 2",
                issueType = "Bug",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(3.days),
                createdAt = oneWeekAgo.plus(1.days),
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User",
                parentKey = "TEST-4"
            ),

            // Test Project 2 - Epic 1 (In Progress with low completion rate)
            "PROJ2-1" to ProjectIssue(
                id = "101",
                key = "PROJ2-1",
                url = "https://jira.example.local/browse/PROJ2-1",
                title = "Project 2 Epic 1",
                description = "This is an epic for Project 2 with low completion rate",
                issueType = "Epic",
                status = "In Progress",
                statusCategory = StatusCategory.IN_PROGRESS,
                completedAt = null,
                createdAt = oneWeekAgo,
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = null
            ),
            "PROJ2-3" to ProjectIssue(
                id = "103",
                key = "PROJ2-3",
                url = "https://jira.example.local/browse/PROJ2-3",
                title = "Project 2 Story 1",
                description = "This is a story for Project 2 Epic 1",
                issueType = "Story",
                status = "In Progress",
                statusCategory = StatusCategory.IN_PROGRESS,
                completedAt = null,
                createdAt = oneWeekAgo.plus(2.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "PROJ2-1"
            ),
            "PROJ2-4" to ProjectIssue(
                id = "104",
                key = "PROJ2-4",
                url = "https://jira.example.local/browse/PROJ2-4",
                title = "Project 2 Task 1",
                description = "This is a task for Project 2 Epic 1",
                issueType = "Task",
                status = "In Progress",
                statusCategory = StatusCategory.IN_PROGRESS,
                completedAt = null,
                createdAt = oneWeekAgo.plus(3.days),
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User",
                parentKey = "PROJ2-1"
            ),
            "PROJ2-5" to ProjectIssue(
                id = "105",
                key = "PROJ2-5",
                url = "https://jira.example.local/browse/PROJ2-5",
                title = "Project 2 Bug 1",
                description = "This is a bug for Project 2 Epic 1",
                issueType = "Bug",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(1.days),
                createdAt = oneWeekAgo.plus(4.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "PROJ2-1"
            ),

            // Test Project 2 - Epic 2 (Completed the milestone)
            "PROJ2-2" to ProjectIssue(
                id = "102",
                key = "PROJ2-2",
                url = "https://jira.example.local/browse/PROJ2-2",
                title = "Project 2 Epic 2",
                description = "This is a completed epic for Project 2",
                issueType = "Epic",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(2.days),
                createdAt = oneWeekAgo,
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User",
                parentKey = null
            ),
            "PROJ2-6" to ProjectIssue(
                id = "106",
                key = "PROJ2-6",
                url = "https://jira.example.local/browse/PROJ2-6",
                title = "Project 2 Story 2",
                description = "This is a story for Project 2 Epic 2",
                issueType = "Story",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(3.days),
                createdAt = oneWeekAgo.plus(1.days),
                assigneeId = "jira-user-1",
                assigneeName = "Test User",
                parentKey = "PROJ2-2"
            ),
            "PROJ2-7" to ProjectIssue(
                id = "107",
                key = "PROJ2-7",
                url = "https://jira.example.local/browse/PROJ2-7",
                title = "Project 2 Task 2",
                description = "This is a task for Project 2 Epic 2",
                issueType = "Task",
                status = "Done",
                statusCategory = StatusCategory.DONE,
                completedAt = now.minus(4.days),
                createdAt = oneWeekAgo.plus(2.days),
                assigneeId = "jira-misc-1",
                assigneeName = "Misc User",
                parentKey = "PROJ2-2"
            )
        )

        override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> {
            return issueKeys.mapNotNull { mockIssues[it] }
        }

        override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
            return mockIssues.values.filter { it.parentKey in issueKeys }.toList()
        }

        override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> {
            return mockIssues.values.filter { it.parentKey == parentKey }.toList()
        }

        override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
            return List(maxResults.coerceAtMost(3)) { index ->
                ProjectComment(
                    id = "$index",
                    body = "This is a test comment for $issueKey",
                    authorId = "jira-user-1",
                    authorName = "Test User",
                    createdAt = now.minus((index + 1).days),
                    updatedAt = now.minus((index + 1).days)
                )
            }
        }

        override suspend fun getIssuesResolved(
            userId: String,
            startDate: Instant,
            endDate: Instant,
        ): List<ProjectIssue> {
            return mockIssues.values.filter { issue ->
                issue.assigneeId == userId &&
                        issue.completedAt?.let { date ->
                            date >= startDate && date <= endDate
                        } ?: false
            }.toList()
        }

        override suspend fun getIssuesResolvedCount(
            userId: String,
            startDate: Instant,
            endDate: Instant,
        ): UInt {
            return getIssuesResolved(userId, startDate, endDate).size.toUInt()
        }

        override suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> {
            val completedAfter = filter.completedAfter
            return mockIssues.values.filter { issue ->
                val typeMatches = filter.issueTypes?.any { it.equals(issue.issueType, ignoreCase = true) } ?: true
                val dateMatches =
                    completedAfter == null || (issue.completedAt?.let { it >= completedAfter } ?: false)
                typeMatches && dateMatches
            }
        }

        override suspend fun getMilestones(projectId: String): List<ProjectMilestone> {
            return emptyList()
        }

        override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> {
            return getDirectChildIssues(milestoneId)
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
            // PRs for Test Project 1
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
            ),
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/test-repo/issues/3",
                repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
                id = 3L,
                number = 3,
                state = "closed",
                title = "Test PR 3",
                user = mockUser,
                body = "This is a test PR that implements TEST-6",
                htmlUrl = "https://github.com/test-org/test-repo/pull/3",
                labels = listOf(Label(name = "feature", id = 3L, color = "blue", description = "New feature")),
                draft = false,
                createdAt = oneWeekAgo.plus(2.days),
                updatedAt = now.minus(2.days),
                closedAt = now.minus(2.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/test-repo/pulls/3",
                    htmlUrl = "https://github.com/test-org/test-repo/pull/3",
                    mergedAt = now.minus(2.days)
                ),
                comments = 3
            ),
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/test-repo/issues/4",
                repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
                id = 4L,
                number = 4,
                state = "closed",
                title = "Test PR 4",
                user = mockUser,
                body = "This is a test PR that fixes TEST-7",
                htmlUrl = "https://github.com/test-org/test-repo/pull/4",
                labels = listOf(Label(name = "bug", id = 1L, color = "red", description = "Bug fix")),
                draft = false,
                createdAt = oneWeekAgo.plus(3.days),
                updatedAt = now.minus(3.days),
                closedAt = now.minus(3.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/test-repo/pulls/4",
                    htmlUrl = "https://github.com/test-org/test-repo/pull/4",
                    mergedAt = now.minus(3.days)
                ),
                comments = 1
            ),

            // PRs for Test Project 2
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/project2-repo/issues/1",
                repositoryUrl = "https://api.github.com/repos/test-org/project2-repo",
                id = 101L,
                number = 1,
                state = "closed",
                title = "Project 2 PR 1",
                user = mockUser,
                body = "This PR fixes PROJ2-5",
                htmlUrl = "https://github.com/test-org/project2-repo/pull/1",
                labels = listOf(Label(name = "bug", id = 1L, color = "red", description = "Bug fix")),
                draft = false,
                createdAt = oneWeekAgo.plus(4.days),
                updatedAt = now.minus(1.days),
                closedAt = now.minus(1.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/project2-repo/pulls/1",
                    htmlUrl = "https://github.com/test-org/project2-repo/pull/1",
                    mergedAt = now.minus(1.days)
                ),
                comments = 2
            ),
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/project2-repo/issues/2",
                repositoryUrl = "https://api.github.com/repos/test-org/project2-repo",
                id = 102L,
                number = 2,
                state = "closed",
                title = "Project 2 PR 2",
                user = mockUser,
                body = "This PR implements PROJ2-6",
                htmlUrl = "https://github.com/test-org/project2-repo/pull/2",
                labels = listOf(Label(name = "enhancement", id = 2L, color = "green", description = "Enhancement")),
                draft = false,
                createdAt = oneWeekAgo.plus(2.days),
                updatedAt = now.minus(3.days),
                closedAt = now.minus(3.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/project2-repo/pulls/2",
                    htmlUrl = "https://github.com/test-org/project2-repo/pull/2",
                    mergedAt = now.minus(3.days)
                ),
                comments = 1
            ),
            GitHubIssue(
                url = "https://api.github.com/repos/test-org/project2-repo/issues/3",
                repositoryUrl = "https://api.github.com/repos/test-org/project2-repo",
                id = 103L,
                number = 3,
                state = "closed",
                title = "Project 2 PR 3",
                user = mockUser,
                body = "This PR implements PROJ2-7",
                htmlUrl = "https://github.com/test-org/project2-repo/pull/3",
                labels = listOf(Label(name = "feature", id = 3L, color = "blue", description = "New feature")),
                draft = false,
                createdAt = oneWeekAgo.plus(3.days),
                updatedAt = now.minus(4.days),
                closedAt = now.minus(4.days),
                pullRequest = PullRequest(
                    url = "https://api.github.com/repos/test-org/project2-repo/pulls/3",
                    htmlUrl = "https://github.com/test-org/project2-repo/pull/3",
                    mergedAt = now.minus(4.days)
                ),
                comments = 3
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

        override suspend fun getPullRequestReviewCount(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: Instant,
            endDate: Instant,
        ): UInt {
            // For summary tests we don't currently track reviews separately.
            // Return 0 to satisfy the interface; user-metrics publisher uses the real implementation.
            return 0u
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

        override suspend fun listNotifications(): List<com.github.karlsabo.github.Notification> {
            return emptyList()
        }

        override suspend fun getPullRequestByUrl(url: String): PullRequest {
            TODO("Not yet implemented")
        }

        override suspend fun approvePullRequestByUrl(url: String, body: String?) {
            TODO("Not yet implemented")
        }

        override suspend fun markNotificationAsDone(threadId: String) {
            // no-op for tests
        }

        override suspend fun unsubscribeFromNotification(threadId: String) {
            // no-op for tests
        }

        override suspend fun hasAnyApprovedReview(url: String): Boolean {
            TODO("Not yet implemented")
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
                createdAt = oneWeekAgo.plus(1.days),
                lastStatusChangeAt = now.minus(3.days),
                resolvedAt = now.minus(3.days),
                htmlUrl = "https://pagerduty.example.local/incidents/1",
                updatedAt = now.minus(3.days).minus(10.minutes),
                service = Service(id = "PD123")
            ),
            PagerDutyIncident(
                id = "INCIDENT2",
                incidentNumber = 2,
                title = "Test Incident 2",
                description = "This is another test incident",
                status = "resolved",
                urgency = "low",
                service = Service("PD123"),
                createdAt = oneWeekAgo.plus(3.days),
                lastStatusChangeAt = now.minus(1.days),
                resolvedAt = now.minus(1.days),
                htmlUrl = "https://pagerduty.example.local/incidents/2",
                updatedAt = now.minus(1.days),
            )
        )

        override suspend fun getServicePages(
            serviceId: String,
            startTimeInclusive: Instant,
            endTimeExclusive: Instant,
        ): List<PagerDutyIncident> {
            return mockIncidents.filter {
                it.service?.id == serviceId &&
                        it.createdAt >= startTimeInclusive &&
                        it.createdAt < endTimeExclusive
            }
        }
    }
}
