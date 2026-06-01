package com.github.karlsabo.linear

import com.github.karlsabo.common.pagination.collectCursorPaginated
import com.github.karlsabo.common.pagination.collectCursorPaginatedWithLimit
import com.github.karlsabo.common.pagination.extractCursorPage
import com.github.karlsabo.dto.User
import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.conversion.toProjectComment
import com.github.karlsabo.linear.conversion.toProjectIssue
import com.github.karlsabo.linear.conversion.toUnifiedProjectMilestone
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.linear.query.LinearQueryBuilder.Companion.escapeGraphQlString
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.github.karlsabo.projectmanagement.ProjectMilestone as UnifiedProjectMilestone

private val logger = KotlinLogging.logger {}

private const val DEFAULT_PAGE_SIZE = 100
private const val DEFAULT_BATCH_SIZE = 100
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

class LinearApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

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
@Suppress("TooManyFunctions")
class LinearRestApi(
    private val config: LinearApiRestConfig,
    private val clientOverride: HttpClient? = null,
) : ProjectManagementApi {
    @Serializable
    private data class GraphQlRequest(
        val query: String,
        val variables: JsonObject? = null,
    )

    private val queryBuilder = LinearQueryBuilder(DEFAULT_PAGE_SIZE)

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
            val query = queryBuilder.issuesByIds(batch, ISSUE_FIELDS)
            val data = executeGraphQl(query)
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

    override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()

        val children = issueKeys.flatMap { key ->
            if (isIssueIdentifier(key)) {
                fetchChildIssues(key)
            } else {
                fetchProjectIssues(key)
            }
        }

        return children.map { it.toProjectIssue() }
    }

    override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> = fetchChildIssues(parentKey).map { it.toProjectIssue() }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
        if (maxResults <= 0) return emptyList()

        val escapedIssueKey = escapeGraphQlString(issueKey)
        return collectCursorPaginatedWithLimit(
            maxItems = maxResults,
            fetchPage = { cursor, pageSize ->
                executeGraphQl(queryBuilder.issueComments(escapedIssueKey, COMMENT_FIELDS, pageSize, cursor))
            },
            extractPage = { data -> data.extractCursorPage("issue", "comments") },
            transform = { node -> lenientJson.decodeFromJsonElement(Comment.serializer(), node).toProjectComment() },
        )
    }

    override suspend fun getIssuesResolved(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): List<ProjectIssue> {
        val userId = user.linearId ?: user.id
        val filter = queryBuilder.resolvedIssuesFilter(userId, startDate, endDate)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    override suspend fun getIssuesResolvedCount(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val userId = user.linearId ?: user.id
        val filter = queryBuilder.resolvedIssuesFilter(userId, startDate, endDate)
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

        val filter = queryBuilder.labelsFilter(labels, completedAfter, completedBefore)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    private suspend fun fetchProjectIssues(projectId: String): List<Issue> = collectCursorPaginated(
        fetchPage = { cursor -> executeGraphQl(queryBuilder.projectIssues(projectId, ISSUE_FIELDS, cursor)) },
        extractPage = { data -> data.extractCursorPage("project", "issues") },
        transform = ::decodeIssue,
    )

    override suspend fun getMilestones(projectId: String): List<UnifiedProjectMilestone> {
        val escapedProjectId = escapeGraphQlString(projectId)
        return collectCursorPaginated(
            fetchPage = { cursor ->
                executeGraphQl(queryBuilder.projectMilestones(escapedProjectId, MILESTONE_FIELDS, cursor))
            },
            extractPage = { data -> data.extractCursorPage("project", "projectMilestones") },
            transform = { node ->
                lenientJson.decodeFromJsonElement(ProjectMilestone.serializer(), node).toUnifiedProjectMilestone()
            },
        )
    }

    override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> {
        val filter = queryBuilder.milestoneIssuesFilter(milestoneId)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt").map { it.toProjectIssue() }
    }

    private suspend fun fetchChildIssues(issueKey: String): List<Issue> {
        val escapedIssueKey = escapeGraphQlString(issueKey)
        return collectCursorPaginated(
            fetchPage = { cursor -> executeGraphQl(queryBuilder.childrenOf(escapedIssueKey, ISSUE_FIELDS, cursor)) },
            extractPage = { data -> data.extractCursorPage("issue", "children") },
            transform = ::decodeIssue,
        )
    }

    private suspend fun fetchIssuesByFilter(
        filter: String,
        selection: String,
        orderBy: String? = null,
    ): List<Issue> = collectCursorPaginated(
        fetchPage = { cursor -> executeGraphQl(queryBuilder.issuesByFilter(filter, selection, cursor, orderBy)) },
        extractPage = { data -> data.extractCursorPage("issues") },
        transform = ::decodeIssue,
    )

    private suspend fun countIssuesByFilter(filter: String): UInt = collectCursorPaginated(
        fetchPage = { cursor -> executeGraphQl(queryBuilder.issuesByFilter(filter, ISSUE_ID_FIELDS, cursor, null)) },
        extractPage = { data -> data.extractCursorPage("issues") },
        transform = { },
    ).size.toUInt()

    private suspend fun executeGraphQl(query: String, variables: JsonObject? = null): JsonObject {
        val response = client.post(config.endpoint) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(GraphQlRequest(query, variables))
        }

        val responseText = response.bodyAsText()
        ensureGraphQlHttpSuccess(response.status, responseText)

        val root = lenientJson.parseToJsonElement(responseText).jsonObject
        ensureGraphQlResponseHasNoErrors(root, query)

        return root["data"]?.jsonObject ?: missingGraphQlData(responseText)
    }

    /**
     * Returns true if the key looks like a Linear issue identifier (e.g., "ENG-123"),
     * false if it looks like a project identifier (typically a UUID).
     */
    private fun isIssueIdentifier(key: String): Boolean = key.contains(Regex("^[A-Za-z]+-\\d+$"))

    private fun authorizationHeaderValue(): String {
        if (config.token.startsWith("Bearer ")) return config.token
        return if (config.useBearerAuth) {
            "Bearer ${config.token}"
        } else {
            config.token
        }
    }
}

private fun decodeIssue(
    node: kotlinx.serialization.json.JsonElement,
): Issue = lenientJson.decodeFromJsonElement(Issue.serializer(), node)

private fun ensureGraphQlHttpSuccess(status: HttpStatusCode, responseText: String) {
    if (status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
        logger.debug { "Linear GraphQL error response: ```$responseText```" }
        throw LinearApiException("Failed Linear GraphQL request: ${status.value}")
    }
}

private fun ensureGraphQlResponseHasNoErrors(root: JsonObject, query: String) {
    val errors = root["errors"]?.jsonArray
        ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.content }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    if (errors.isNotEmpty()) {
        val errorDetails = root["errors"]?.jsonArray
            ?.map { it.jsonObject.toString() }
            ?.joinToString("\n") ?: ""
        logger.error { "Linear GraphQL errors for query:\n$query\nErrors:\n$errorDetails" }
        throw LinearApiException("Linear GraphQL errors: ${errors.joinToString("; ")}")
    }
}

private fun missingGraphQlData(responseText: String): Nothing = throw LinearApiException("Linear GraphQL response missing data: $responseText")
