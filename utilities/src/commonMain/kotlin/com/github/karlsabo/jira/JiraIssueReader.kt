package com.github.karlsabo.jira

import com.github.karlsabo.dto.User
import com.github.karlsabo.jira.config.JiraApiRestConfig
import com.github.karlsabo.jira.conversion.toProjectIssue
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

internal class JiraIssueReader(
    private val config: JiraApiRestConfig,
    private val httpApi: JiraHttpApi,
    private val jqlSearch: JiraJqlSearchClient,
    private val labelCustomFieldId: String?,
) {
    suspend fun runJql(jql: String): List<ProjectIssue> = jqlSearch.run(jql) { it.toProjectIssue() }

    suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql("key in (${issueKeys.joinToString(",")})")
    }

    suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql(issueKeys.joinToString(" OR ") { key -> "issuekey in portfolioChildIssuesOf(\"$key\")" })
    }

    suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> = runJql("parent = $parentKey")

    suspend fun getIssuesResolved(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): List<ProjectIssue> {
        val userId = user.jiraId ?: user.id
        return runJql("${JiraJqlBuilder.resolvedIssues(userId, startDate, endDate)} ORDER BY resolutiondate DESC")
    }

    suspend fun getIssuesResolvedCount(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val userId = user.jiraId ?: user.id
        val jql = JiraJqlBuilder.resolvedIssues(userId, startDate, endDate)
        val url = "https://${config.domain}/rest/api/3/search/approximate-count"
        val root = httpApi.postJson(url, mapOf("jql" to jql), "get issues resolved count for jql=$jql")
        return (root["count"]?.jsonPrimitive?.int ?: 0).toUInt()
    }

    suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> = when {
        labelCustomFieldId != null && !filter.labels.isNullOrEmpty() -> getIssuesByCustomFieldFilter(
            issueTypes = filter.issueTypes ?: emptyList(),
            customFieldFilter = CustomFieldFilter(labelCustomFieldId, filter.labels),
            resolvedAfter = filter.completedAfter,
            resolvedBefore = filter.completedBefore,
        )

        !filter.issueTypes.isNullOrEmpty() -> runJql(JiraJqlBuilder.issueFilter(filter))

        else -> emptyList()
    }

    suspend fun getIssuesByCustomFieldFilter(
        issueTypes: List<String>,
        customFieldFilter: CustomFieldFilter,
        resolvedAfter: Instant? = null,
        resolvedBefore: Instant? = null,
    ): List<ProjectIssue> = runJql(
        JiraJqlBuilder.customFieldIssueQuery(
            issueTypes = issueTypes,
            customFieldFilter = customFieldFilter,
            resolvedAfter = resolvedAfter,
            resolvedBefore = resolvedBefore,
        ),
    )
}
