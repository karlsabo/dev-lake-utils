package com.github.karlsabo.linear

import com.github.karlsabo.dto.User
import com.github.karlsabo.linear.conversion.toProjectIssue
import com.github.karlsabo.linear.query.LinearIssueFilterBuilder
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.tools.lenientJson
import kotlinx.serialization.json.JsonNull
import kotlin.time.Instant

internal class LinearIssueReader(
    private val graphQlClient: LinearGraphQlClient,
    private val queryBuilder: LinearQueryBuilder,
) {
    private val pager = LinearIssuePager(graphQlClient, queryBuilder)
    private val filterBuilder = LinearIssueFilterBuilder()

    suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()

        val issues = mutableListOf<Issue>()
        issueKeys.chunked(LINEAR_DEFAULT_BATCH_SIZE).forEach { batch ->
            val query = queryBuilder.issuesByIds(batch, LINEAR_ISSUE_FIELDS)
            val data = graphQlClient.execute(query)
            batch.indices.forEach { index ->
                val alias = "issue$index"
                val issueNode = data[alias]
                if (issueNode != null && issueNode !is JsonNull) {
                    issues += lenientJson.decodeFromJsonElement(Issue.serializer(), issueNode)
                }
            }
        }

        return issues.map { it.toProjectIssue() }
    }

    suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()

        val children = issueKeys.flatMap { key ->
            if (isIssueIdentifier(key)) {
                pager.fetchChildIssues(key)
            } else {
                pager.fetchProjectIssues(key)
            }
        }

        return children.map { it.toProjectIssue() }
    }

    suspend fun getDirectChildIssues(
        parentKey: String,
    ): List<ProjectIssue> = pager.fetchChildIssues(parentKey).map { it.toProjectIssue() }

    suspend fun getIssuesResolved(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): List<ProjectIssue> {
        val userId = user.linearId ?: user.id
        val filter = queryBuilder.resolvedIssuesFilter(userId, startDate, endDate)
        return pager.fetchIssuesByFilter(filter, LINEAR_ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    suspend fun getIssuesResolvedCount(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val userId = user.linearId ?: user.id
        val filter = queryBuilder.resolvedIssuesFilter(userId, startDate, endDate)
        return pager.countIssuesByFilter(filter)
    }

    suspend fun getIssuesByLabels(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): List<ProjectIssue> {
        if (labels.isEmpty()) return emptyList()

        val filter = queryBuilder.labelsFilter(labels, completedAfter, completedBefore)
        return pager.fetchIssuesByFilter(filter, LINEAR_ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    suspend fun getTriageIssues(
        teamKey: String,
        projectName: String?,
        labelName: String?,
    ): List<LinearTriageIssue> {
        require(projectName != null || labelName != null) { "A project or label selector is required" }

        val projectIssues = projectName?.let { fetchTriageIssues(teamKey, projectName = it) }.orEmpty()
        val labelIssues = labelName?.let { fetchTriageIssues(teamKey, labelName = it) }.orEmpty()
        return (projectIssues + labelIssues)
            .distinctBy(Issue::id)
            .map(Issue::toLinearTriageIssue)
    }

    suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> {
        val filter = queryBuilder.milestoneIssuesFilter(milestoneId)
        return pager.fetchIssuesByFilter(filter, LINEAR_ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    private suspend fun fetchTriageIssues(
        teamKey: String,
        projectName: String? = null,
        labelName: String? = null,
    ): List<Issue> {
        val filter = filterBuilder.triageScopeFilter(teamKey, projectName, labelName)
        return pager.fetchIssuesByFilter(
            filter = filter,
            selection = LINEAR_ISSUE_FIELDS,
            orderBy = "updatedAt",
            includeArchived = true,
        )
    }

    private fun isIssueIdentifier(key: String): Boolean = key.contains(Regex("^[A-Za-z]+-\\d+$"))
}

private fun Issue.toLinearTriageIssue(): LinearTriageIssue = LinearTriageIssue(
    id = id,
    identifier = requireNotNull(identifier) { "Linear issue $id has no identifier" },
    title = requireNotNull(title) { "Linear issue $id has no title" },
    description = description,
    url = url,
    stateName = state?.name,
    stateType = state?.type,
    projectName = project?.name,
    labelNames = labels?.nodes.orEmpty().mapTo(mutableSetOf()) { it.name },
    updatedAt = updatedAt,
    completedAt = completedAt,
    canceledAt = canceledAt,
    archivedAt = archivedAt,
)
