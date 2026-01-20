package com.github.karlsabo.linear

import com.github.karlsabo.http.installHttpRetry
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
import io.ktor.utils.io.readText
import kotlinx.datetime.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_LINEAR_ENDPOINT = "https://api.linear.app/graphql"
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

/**
 * Configuration for Linear GraphQL API.
 */
data class LinearApiRestConfig(
    val token: String,
    val endpoint: String = DEFAULT_LINEAR_ENDPOINT,
    val useBearerAuth: Boolean = false,
) {
    override fun toString(): String {
        return "LinearApiRestConfig(endpoint=$endpoint, useBearerAuth=$useBearerAuth)"
    }
}

/**
 * Configuration for Linear API.
 */
@Serializable
data class LinearConfig(
    val tokenPath: String,
    val endpoint: String? = null,
    val useBearerAuth: Boolean? = null,
)

/**
 * Secret configuration for Linear API.
 */
@Serializable
data class LinearSecret(
    val linearApiKey: String,
)

/**
 * Loads Linear configuration from a file.
 */
fun loadLinearConfig(configFilePath: Path): LinearApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<LinearConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<LinearSecret>(source.readText())
    }

    return LinearApiRestConfig(
        token = secretConfig.linearApiKey,
        endpoint = config.endpoint ?: DEFAULT_LINEAR_ENDPOINT,
        useBearerAuth = config.useBearerAuth ?: false,
    )
}

/**
 * Saves Linear configuration to a file.
 */
@Suppress("unused")
fun saveLinearConfig(configPath: Path, config: LinearConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(LinearConfig.serializer(), config))
    }
}

/**
 * Implementation of the Linear API using GraphQL.
 */
class LinearRestApi(private val config: LinearApiRestConfig, private val clientOverride: HttpClient? = null) :
    LinearApi {
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

    override suspend fun getIssues(issueKeys: List<String>): List<Issue> {
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

        return issues
    }

    override suspend fun getChildIssues(issueKeys: List<String>): List<Issue> {
        if (issueKeys.isEmpty()) return emptyList()

        val children = mutableListOf<Issue>()
        for (issueKey in issueKeys) {
            children += fetchChildIssues(issueKey)
        }

        return children
    }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment> {
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

        return comments
    }

    override suspend fun getIssuesResolved(userId: String, startDate: Instant, endDate: Instant): List<Issue> {
        val filter = buildResolvedIssuesFilter(userId, startDate, endDate)
        return fetchIssuesByFilter(filter, ISSUE_FIELDS, "updatedAt")
    }

    override suspend fun getIssuesResolvedCount(userId: String, startDate: Instant, endDate: Instant): UInt {
        val filter = buildResolvedIssuesFilter(userId, startDate, endDate)
        return countIssuesByFilter(filter)
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
