package com.github.karlsabo.tools.formatting

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.Issue
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.tools.model.Milestone
import com.github.karlsabo.tools.model.ProjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import com.github.karlsabo.github.User as GitHubUser

class SlackMarkdownFormatterTest {
    @Test
    fun summaryMarkupIncludesDurationChangesPullRequestsAndRecentMilestones() {
        val resolvedIssue = projectIssue("ENG-1", "Resolved", completed = true)
        val openedIssue = projectIssue("ENG-2", "Opened")
        val milestone = milestone(
            issue = projectIssue("ENG-3", "Recent milestone", completed = true, type = "epic"),
        )
        val summary = projectSummary(
            issues = setOf(resolvedIssue, openedIssue),
            durationIssues = setOf(resolvedIssue, openedIssue),
            durationMergedPullRequests = setOf(pullRequest()),
            milestones = setOf(milestone),
        )

        val expected = buildString {
            appendLine("*<https://project|Project Atlas>*")
            appendLine("🟨🟨🟨🟨🟨⬜⬜⬜⬜⬜ 50% ⚖️ 0 net issues this week")
            appendLine()
            appendLine("One issue resolved, one opened")
            appendLine()
            appendLine("📍 Issues resolved: <https://linear/ENG-1|ENG-1>")
            appendLine("📩 Issues opened: <https://linear/ENG-2|ENG-2>")
            appendLine("🔹 PRs merged: <https://github/pull/42|42>")
            appendLine()
            appendLine("🛣️ *Milestones completed in the last 14 days*")
            appendLine()
            appendLine("*✅ <https://linear/ENG-3|Recent milestone>*")
        }

        assertEquals(expected, summary.toSlackMarkup())
    }

    @Test
    fun verboseSlackMarkdownIncludesOpenMilestoneDueDateWarning() {
        val childIssue = projectIssue("ENG-4", "Child issue")
        val milestone = milestone(
            assignee = User(id = "user-1", name = "Alex", slackId = "U123"),
            issue = projectIssue("ENG-5", "Needs due date", type = "epic", assigneeName = "Alex"),
            issues = setOf(childIssue),
        )
        val summary = projectSummary(milestones = setOf(milestone), isTagMilestoneAssignees = true)

        val expected = buildString {
            appendLine("*<https://project|Project Atlas>*")
            appendLine("⬜⬜⬜⬜⬜⬜⬜⬜⬜⬜ 0% ⚖️ 0 net issues this week")
            appendLine("One issue resolved, one opened")
            appendLine("🛣️ *Milestones*")
            appendLine()
            appendLine()
            appendLine("*<https://linear/ENG-5|Needs due date>: Alex*")
            appendLine("⬜⬜⬜⬜⬜⬜⬜⬜⬜⬜ 0% ⚖️ 0 net issues this week")
            appendLine("Alex <@U123>, please add a due date on the Epic")
        }

        assertEquals(expected, summary.toVerboseSlackMarkdown())
    }

    private fun projectSummary(
        issues: Set<ProjectIssue> = emptySet(),
        durationIssues: Set<ProjectIssue> = emptySet(),
        durationMergedPullRequests: Set<Issue> = emptySet(),
        milestones: Set<Milestone> = emptySet(),
        isTagMilestoneAssignees: Boolean = false,
    ): ProjectSummary = ProjectSummary(
        project = Project(id = 1, title = "Project Atlas", links = listOf("https://project")),
        durationProgressSummary = "One issue resolved, one opened",
        issues = issues,
        durationIssues = durationIssues,
        durationMergedPullRequests = durationMergedPullRequests,
        milestones = milestones,
        isTagMilestoneAssignees = isTagMilestoneAssignees,
    )

    private fun milestone(
        assignee: User? = null,
        issue: ProjectIssue,
        issues: Set<ProjectIssue> = emptySet(),
        durationIssues: Set<ProjectIssue> = emptySet(),
        durationMergedPullRequests: Set<Issue> = emptySet(),
    ): Milestone = Milestone(
        assignee = assignee,
        issue = issue,
        issues = issues,
        milestoneComments = emptySet(),
        durationIssues = durationIssues,
        durationMergedPullRequests = durationMergedPullRequests,
    )

    private fun projectIssue(
        key: String,
        title: String,
        completed: Boolean = false,
        type: String = "issue",
        assigneeName: String? = null,
    ): ProjectIssue = ProjectIssue(
        id = key,
        key = key,
        url = "https://linear/$key",
        title = title,
        issueType = type,
        assigneeName = assigneeName,
        completedAt = if (completed) Clock.System.now() else null,
    )

    private fun pullRequest(): Issue = Issue(
        url = null,
        repositoryUrl = null,
        id = 42,
        number = 42,
        state = "closed",
        title = "Feature PR",
        user = GitHubUser(login = "dev", id = 1, avatarUrl = null, url = null, htmlUrl = null),
        body = null,
        htmlUrl = "https://github/pull/42",
        draft = false,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        closedAt = Clock.System.now(),
        comments = 0,
    )
}
