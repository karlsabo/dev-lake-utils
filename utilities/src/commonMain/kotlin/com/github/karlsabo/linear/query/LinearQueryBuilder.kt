package com.github.karlsabo.linear.query

import kotlinx.datetime.Instant

/** Builder facade for Linear GraphQL queries and filters. */
class LinearQueryBuilder(
    private val defaultPageSize: Int = 100,
) {
    private val issueQueries = LinearIssueQueryBuilder(defaultPageSize)
    private val commentQueries = LinearCommentQueryBuilder(defaultPageSize)
    private val milestoneQueries = LinearMilestoneQueryBuilder(defaultPageSize)
    private val filters = LinearIssueFilterBuilder()

    fun issuesByIds(
        issueKeys: List<String>,
        issueFields: String,
    ): String = issueQueries.issuesByIds(issueKeys, issueFields)

    fun childrenOf(
        issueKey: String,
        issueFields: String,
        cursor: String? = null,
    ): String = issueQueries.childrenOf(issueKey, issueFields, cursor)

    fun issueComments(
        issueKey: String,
        commentFields: String,
        pageSize: Int = defaultPageSize,
        cursor: String? = null,
    ): String = commentQueries.issueComments(issueKey, commentFields, pageSize, cursor)

    fun issuesByFilter(
        filter: String,
        selection: String,
        cursor: String? = null,
        orderBy: String? = null,
    ): String = issueQueries.issuesByFilter(filter, selection, cursor, orderBy)

    fun projectMilestones(
        projectId: String,
        milestoneFields: String,
        cursor: String? = null,
    ): String = milestoneQueries.projectMilestones(projectId, milestoneFields, cursor)

    fun resolvedIssuesFilter(
        userId: String,
        startDate: Instant,
        endDate: Instant,
    ): String = filters.resolvedIssuesFilter(userId, startDate, endDate)

    fun labelsFilter(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): String = filters.labelsFilter(labels, completedAfter, completedBefore)

    fun projectIssues(
        projectId: String,
        issueFields: String,
        cursor: String? = null,
    ): String = issueQueries.projectIssues(projectId, issueFields, cursor)

    fun milestoneIssuesFilter(milestoneId: String): String = filters.milestoneIssuesFilter(milestoneId)

    fun completedAtFilter(
        completedAfter: Instant?,
        completedBefore: Instant?,
    ): String = filters.completedAtFilter(completedAfter, completedBefore)

    companion object {
        fun escapeGraphQlString(value: String): String = escapeLinearGraphQlString(value)
    }
}
