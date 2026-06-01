package com.github.karlsabo.jira

import com.github.karlsabo.dto.User
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.ProjectMilestone
import kotlinx.datetime.Instant

internal class JiraProjectManagementClient(
    private val issueReader: JiraIssueReader,
    private val commentReader: JiraCommentReader,
    private val milestoneReader: JiraMilestoneReader,
) : ProjectManagementApi {
    override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> = issueReader.getIssues(issueKeys)

    override suspend fun getChildIssues(
        issueKeys: List<String>,
    ): List<ProjectIssue> = issueReader.getChildIssues(issueKeys)

    override suspend fun getDirectChildIssues(
        parentKey: String,
    ): List<ProjectIssue> = issueReader.getDirectChildIssues(parentKey)

    override suspend fun getRecentComments(
        issueKey: String,
        maxResults: Int,
    ): List<ProjectComment> = commentReader.getRecentComments(issueKey, maxResults)

    override suspend fun getIssuesResolved(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): List<ProjectIssue> = issueReader.getIssuesResolved(user, startDate, endDate)

    override suspend fun getIssuesResolvedCount(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): UInt = issueReader.getIssuesResolvedCount(user, startDate, endDate)

    override suspend fun getIssuesByFilter(
        filter: IssueFilter,
    ): List<ProjectIssue> = issueReader.getIssuesByFilter(filter)

    override suspend fun getMilestones(
        projectId: String,
    ): List<ProjectMilestone> = milestoneReader.getMilestones(projectId)

    override suspend fun getMilestoneIssues(
        milestoneId: String,
    ): List<ProjectIssue> = issueReader.getDirectChildIssues(milestoneId)
}
