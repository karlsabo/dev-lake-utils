package com.github.karlsabo.linear

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.conversion.toProjectComment
import com.github.karlsabo.linear.conversion.toProjectIssue
import com.github.karlsabo.linear.conversion.toUnifiedProjectMilestone
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.github.karlsabo.projectmanagement.ProjectMilestone as UnifiedProjectMilestone

private const val DEFAULT_PAGE_SIZE = 100
private const val DEFAULT_BATCH_SIZE = 100

private val ISSUE_FIELDS = """
            id
            identifier
            title
            description
            url
            createdAt
            updatedAt
            completedAt
            archivedAt
            canceledAt
            dueDate
            priority
            estimate
            assignee {
              id
              name
              displayName
              email
            }
            creator {
              id
              name
              displayName
              email
            }
            state {
              id
              name
              type
            }
            parent {
              id
              identifier
              title
            }
        """.trimIndent()

private val ISSUE_ID_FIELDS = """
            id
        """.trimIndent()

private val COMMENT_FIELDS = """
            id
            body
            createdAt
            updatedAt
            url
            archivedAt
            user {
              id
              name
              displayName
              email
            }
        """.trimIndent()

private val MILESTONE_FIELDS = """
            id
            name
            description
            targetDate
            progress
        """.trimIndent()

/**
 * Linear GraphQL API implementation of ProjectManagementApi.
 */
class LinearRestApi(private val config: LinearApiRestConfig, private val clientOverride: HttpClient? = null) :
    ProjectManagementApi {
    @Serializable
    private data class GraphQlRequest(
        val query: String,
        val variables: JsonObject? = null,
    )

    private val client: HttpClient by lazy {
        clientOverride ?: buildDefaultClient()
    }

    private fun buildDefaultClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(lenientJson)
        }
        defaultRequest {
            header(HttpHeaders.Authorization, authorizationHeaderValue())
            header(HttpHeaders.Accept, ContentType.Application.Json)
        }
        installHttpRetry()
        install(HttpCache)
        expectSuccess = false
    }

    override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()

        val issues = mutableListOf<Issue>()
        issueKeys.chunked(DEFAULT_BATCH_SIZE).forEach { batch ->
            val query = buildIssuesByIdQuery(batch)
            val data = executeGraphQl(query)
            batch.indices.forEach { index ->
                val alias = "issue$index"
                val issueNode = data[alias]
                if (issueNode == null || issueNode is JsonNull) return@forEach
                issues += lenientJson.decodeFromJsonElement(Issue.serializer(), issueNode)
            }
        }

        return issues.map { it.toProjectIssue() }
    }

    override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()

        val children = mutableListOf<Issue>()
        for (issueKey in issueKeys) {
            children += fetchChildIssues(issueKey)
        }

        return children.map { it.toProjectIssue() }
    }

    override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> {
        return fetchChildIssues(parentKey).map { it.toProjectIssue() }
    }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
        if (maxResults <= 0) return emptyList()

        val comments = mutableListOf<Comment>()
        var remaining = maxResults
        var cursor: String? = null
        val escapedIssueKey = escapeGraphQlString(issueKey)

        while (remaining > 0) {
            val pageSize = minOf(DEFAULT_PAGE_SIZE, remaining)
            val query = buildIssueCommentsQuery(escapedIssueKey, pageSize, cursor)
            val data = executeGraphQl(query)
            val issueNode = data["issue"]?.jsonObject ?: break
            val commentsNode = issueNode["comments"]?.jsonObject ?: break
            val nodes = commentsNode["nodes"]?.jsonArray ?: break

            nodes.forEach { node ->
                comments += lenientJson.decodeFromJsonElement(Comment.serializer(), node)
            }

            val pageInfo = commentsNode["pageInfo"]?.jsonObject
            val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
            val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content

            if (!hasNextPage || endCursor.isNullOrBlank()) break

            cursor = endCursor
            remaining -= nodes.size
            if (nodes.isEmpty()) break
        }

        return comments.map { it.toProjectComment() }
    }

    override suspend fun getIssuesResolved(userId: String, startDate: Instant, endDate: Instant): List<ProjectIssue> {
        val filter = buildResolvedIssuesFilter(userId, startDate, endDate)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    override suspend fun getIssuesResolvedCount(userId: String, startDate: Instant, endDate: Instant): UInt {
        val filter = buildResolvedIssuesFilter(userId, startDate, endDate)
        return countIssuesByFilter(filter)
    }

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

    /**
     * Retrieves issues that have the specified labels.
     * This is a Linear-specific method not part of the ProjectManagementApi interface.
     *
     * @param labels List of label names to filter by
     * @param completedAfter Only return issues completed after this time (optional)
     * @param completedBefore Only return issues completed before this time (optional)
     * @return List of issues matching the label criteria
     */
    suspend fun getIssuesByLabels(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): List<ProjectIssue> {
        if (labels.isEmpty()) return emptyList()

        val filter = buildLabelsFilter(labels, completedAfter, completedBefore)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    override suspend fun getMilestones(projectId: String): List<UnifiedProjectMilestone> {
        val milestones = mutableListOf<ProjectMilestone>()
        var cursor: String? = null
        val escapedProjectId = escapeGraphQlString(projectId)

        while (true) {
            val query = buildProjectMilestonesQuery(escapedProjectId, cursor)
            val data = executeGraphQl(query)
            val projectNode = data["project"]?.jsonObject ?: break
            val milestonesNode = projectNode["projectMilestones"]?.jsonObject ?: break
            val nodes = milestonesNode["nodes"]?.jsonArray ?: break

            nodes.forEach { node ->
                milestones += lenientJson.decodeFromJsonElement(ProjectMilestone.serializer(), node)
            }

            val pageInfo = milestonesNode["pageInfo"]?.jsonObject
            val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
            val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content

            if (!hasNextPage || endCursor.isNullOrBlank()) break
            cursor = endCursor
            if (nodes.isEmpty()) break
        }

        return milestones.map { it.toUnifiedProjectMilestone() }
    }

    override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> {
        val filter = buildMilestoneIssuesFilter(milestoneId)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    private suspend fun fetchChildIssues(issueKey: String): List<Issue> {
        val children = mutableListOf<Issue>()
        var cursor: String? = null
        val escapedIssueKey = escapeGraphQlString(issueKey)

        while (true) {
            val query = buildChildrenQuery(escapedIssueKey, cursor)
            val data = executeGraphQl(query)
            val issueNode = data["issue"]?.jsonObject ?: break
            val childrenNode = issueNode["children"]?.jsonObject ?: break
            val nodes = childrenNode["nodes"]?.jsonArray ?: break

            nodes.forEach { node ->
                children += lenientJson.decodeFromJsonElement(Issue.serializer(), node)
            }

            val pageInfo = childrenNode["pageInfo"]?.jsonObject
            val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
            val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content

            if (!hasNextPage || endCursor.isNullOrBlank()) break
            cursor = endCursor
            if (nodes.isEmpty()) break
        }

        return children
    }

    private suspend fun fetchIssuesByFilter(filter: String, selection: String, orderBy: String? = null): List<Issue> {
        val issues = mutableListOf<Issue>()
        var cursor: String? = null

        while (true) {
            val query = buildIssuesByFilterQuery(filter, selection, cursor, orderBy)
            val data = executeGraphQl(query)
            val issuesNode = data["issues"]?.jsonObject ?: break
            val nodes = issuesNode["nodes"]?.jsonArray ?: break

            nodes.forEach { node ->
                issues += lenientJson.decodeFromJsonElement(Issue.serializer(), node)
            }

            val pageInfo = issuesNode["pageInfo"]?.jsonObject
            val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
            val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content

            if (!hasNextPage || endCursor.isNullOrBlank()) break
            cursor = endCursor
            if (nodes.isEmpty()) break
        }

        return issues
    }

    private suspend fun countIssuesByFilter(filter: String): UInt {
        var cursor: String? = null
        var count = 0

        while (true) {
            val query = buildIssuesByFilterQuery(filter, ISSUE_ID_FIELDS, cursor, null)
            val data = executeGraphQl(query)
            val issuesNode = data["issues"]?.jsonObject ?: break
            val nodes = issuesNode["nodes"]?.jsonArray ?: break

            count += nodes.size

            val pageInfo = issuesNode["pageInfo"]?.jsonObject
            val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
            val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content

            if (!hasNextPage || endCursor.isNullOrBlank()) break
            cursor = endCursor
            if (nodes.isEmpty()) break
        }

        return count.toUInt()
    }

    private suspend fun executeGraphQl(query: String, variables: JsonObject? = null): JsonObject {
        val response = client.post(config.endpoint) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(GraphQlRequest(query, variables))
        }

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("Response: ```$responseText```")
            throw Exception("Failed Linear GraphQL request: ${response.status.value}")
        }

        val root = lenientJson.parseToJsonElement(responseText).jsonObject
        val errors = root["errors"]?.jsonArray
            ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.content }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (errors.isNotEmpty()) {
            throw Exception("Linear GraphQL errors: ${errors.joinToString("; ")}")
        }

        return root["data"]?.jsonObject
            ?: throw Exception("Linear GraphQL response missing data: $responseText")
    }

    private fun buildIssuesByIdQuery(issueKeys: List<String>): String {
        return buildString {
            append("query {")
            issueKeys.forEachIndexed { index, issueKey ->
                val escaped = escapeGraphQlString(issueKey)
                append("\n  issue$index: issue(id: \"")
                append(escaped)
                append("\") {")
                append("\n")
                append(ISSUE_FIELDS.indent("    "))
                append("\n  }")
            }
            append("\n}")
        }
    }

    private fun buildChildrenQuery(issueKey: String, cursor: String?): String {
        return buildString {
            append("query {")
            append("\n  issue(id: \"")
            append(issueKey)
            append("\") {")
            append("\n    children(first: ")
            append(DEFAULT_PAGE_SIZE)
            if (!cursor.isNullOrBlank()) {
                append(", after: \"")
                append(escapeGraphQlString(cursor))
                append("\"")
            }
            append(") {")
            append("\n      nodes {")
            append("\n")
            append(ISSUE_FIELDS.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    private fun buildIssueCommentsQuery(issueKey: String, pageSize: Int, cursor: String?): String {
        return buildString {
            append("query {")
            append("\n  issue(id: \"")
            append(issueKey)
            append("\") {")
            append("\n    comments(first: ")
            append(pageSize)
            if (!cursor.isNullOrBlank()) {
                append(", after: \"")
                append(escapeGraphQlString(cursor))
                append("\"")
            }
            append(", orderBy: updatedAt) {")
            append("\n      nodes {")
            append("\n")
            append(COMMENT_FIELDS.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    private fun buildIssuesByFilterQuery(
        filter: String,
        selection: String,
        cursor: String?,
        orderBy: String?,
    ): String {
        return buildString {
            append("query {")
            append("\n  issues(first: ")
            append(DEFAULT_PAGE_SIZE)
            if (!cursor.isNullOrBlank()) {
                append(", after: \"")
                append(escapeGraphQlString(cursor))
                append("\"")
            }
            if (!orderBy.isNullOrBlank()) {
                append(", orderBy: ")
                append(orderBy)
            }
            append(", filter: ")
            append(filter)
            append(") {")
            append("\n    nodes {")
            append("\n")
            append(selection.indent("      "))
            append("\n    }")
            append("\n    pageInfo { hasNextPage endCursor }")
            append("\n  }")
            append("\n}")
        }
    }

    private fun buildResolvedIssuesFilter(userId: String, startDate: Instant, endDate: Instant): String {
        val escapedUserId = escapeGraphQlString(userId)
        val start = escapeGraphQlString(startDate.toString())
        val end = escapeGraphQlString(endDate.toString())

        return """
            {
              assignee: { id: { eq: "$escapedUserId" } }
              completedAt: { gte: "$start", lte: "$end" }
            }
        """.trimIndent()
    }

    private fun buildLabelsFilter(
        labels: List<String>,
        completedAfter: Instant?,
        completedBefore: Instant?,
    ): String {
        val labelNames = labels.joinToString(", ") { "\"${escapeGraphQlString(it)}\"" }
        val completedFilter = buildCompletedAtFilter(completedAfter, completedBefore)

        return buildString {
            append("{ labels: { name: { in: [$labelNames] } }")
            if (completedFilter.isNotEmpty()) {
                append(", $completedFilter")
            }
            append(" }")
        }
    }

    private fun buildCompletedAtFilter(completedAfter: Instant?, completedBefore: Instant?): String {
        if (completedAfter == null && completedBefore == null) return ""

        return buildString {
            append("completedAt: { ")
            val filters = mutableListOf<String>()
            if (completedAfter != null) {
                filters.add("gte: \"${escapeGraphQlString(completedAfter.toString())}\"")
            }
            if (completedBefore != null) {
                filters.add("lte: \"${escapeGraphQlString(completedBefore.toString())}\"")
            }
            append(filters.joinToString(", "))
            append(" }")
        }
    }

    private fun buildProjectMilestonesQuery(projectId: String, cursor: String?): String {
        return buildString {
            append("query {")
            append("\n  project(id: \"")
            append(projectId)
            append("\") {")
            append("\n    projectMilestones(first: ")
            append(DEFAULT_PAGE_SIZE)
            if (!cursor.isNullOrBlank()) {
                append(", after: \"")
                append(escapeGraphQlString(cursor))
                append("\"")
            }
            append(") {")
            append("\n      nodes {")
            append("\n")
            append(MILESTONE_FIELDS.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    private fun buildMilestoneIssuesFilter(milestoneId: String): String {
        val escapedMilestoneId = escapeGraphQlString(milestoneId)
        return "{ projectMilestone: { id: { eq: \"$escapedMilestoneId\" } } }"
    }

    private fun authorizationHeaderValue(): String {
        if (config.token.startsWith("Bearer ")) return config.token
        return if (config.useBearerAuth) {
            "Bearer ${config.token}"
        } else {
            config.token
        }
    }

    private fun escapeGraphQlString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun String.indent(prefix: String): String {
        return this.lines().joinToString("\n") { line -> if (line.isBlank()) line else prefix + line }
    }
}
