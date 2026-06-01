package com.github.karlsabo.linear

import com.github.karlsabo.dto.User
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import io.ktor.client.HttpClient
import kotlinx.datetime.Instant
import com.github.karlsabo.projectmanagement.ProjectMilestone as UnifiedProjectMilestone

class LinearApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Linear GraphQL API implementation of ProjectManagementApi. */
class LinearRestApi(
    config: LinearApiRestConfig,
    clientOverride: HttpClient? = null,
) : ProjectManagementApi {
    private val graphQlClient = LinearGraphQlClient(config, clientOverride)
    private val queryBuilder = LinearQueryBuilder(LINEAR_DEFAULT_PAGE_SIZE)
    private val issueReader = LinearIssueReader(graphQlClient, queryBuilder)
    private val commentReader = LinearCommentReader(graphQlClient, queryBuilder)
    private val milestoneReader = LinearMilestoneReader(graphQlClient, queryBuilder)

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

    override suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> {
        if (!filter.labels.isNullOrEmpty()) {
            return getIssuesByLabels(
                labels = filter.labels,
                completedAfter = filter.completedAfter,
                completedBefore = filter.completedBefore,
            )
        }

        return emptyList()
    }

    /** Retrieves issues that have the specified labels. */
    suspend fun getIssuesByLabels(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): List<ProjectIssue> = issueReader.getIssuesByLabels(labels, completedAfter, completedBefore)

    override suspend fun getMilestones(
        projectId: String,
    ): List<UnifiedProjectMilestone> = milestoneReader.getMilestones(projectId)

    override suspend fun getMilestoneIssues(
        milestoneId: String,
    ): List<ProjectIssue> = issueReader.getMilestoneIssues(milestoneId)
}
