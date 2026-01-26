package com.github.karlsabo.jira

import com.github.karlsabo.Credentials
import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.ProjectMilestone
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readText
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.github.karlsabo.projectmanagement.StatusCategory as UnifiedStatusCategory

data class JiraApiRestConfig(
    val credentials: Credentials,
    val domain: String,
)

@Serializable
data class JiraConfig(
    val domain: String,
    val username: String,
    val apiKeyPath: String,
)

@Serializable
data class JiraSecret(val jiraApiKey: String)

fun loadJiraConfig(configFilePath: Path): JiraApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<JiraConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.apiKeyPath)).buffered().use { source ->
        lenientJson.decodeFromString<JiraSecret>(source.readText())
    }

    return JiraApiRestConfig(
        Credentials(
            config.username,
            secretConfig.jiraApiKey,
        ),
        config.domain,
    )
}

fun saveJiraConfig(configPath: Path, config: JiraConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(JiraConfig.serializer(), config))
    }
}

/**
 * Jira REST API implementation of ProjectManagementApi.
 *
 * @param config The Jira API configuration
 * @param labelCustomFieldId Optional custom field ID used for labels in Jira (e.g., "customfield_12345")
 */
class JiraRestApi(
    private val config: JiraApiRestConfig,
    private val labelCustomFieldId: String? = null,
) : ProjectManagementApi {
    constructor(config: JiraApiRestConfig, httpClient: HttpClient) : this(config) {
        clientOverride = httpClient
    }

    private var clientOverride: HttpClient? = null

    private val client: HttpClient by lazy {
        clientOverride ?: buildDefaultClient()
    }

    private fun buildDefaultClient(): HttpClient = HttpClient(CIO) {
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(
                        username = config.credentials.username,
                        password = config.credentials.password,
                    )
                }
                sendWithoutRequest { true }
            }
        }
        install(ContentNegotiation) {
            json(lenientJson)
        }
        installHttpRetry()
        install(HttpCache)
        expectSuccess = false
    }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
        val url = "https://${config.domain}/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=1&startAt=0"
        val response = client.get(url).also { it.ensureSuccess("get comments for issueKey=$issueKey") }
        val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return root["comments"]?.jsonArray
            ?.map { it.jsonObject.toComment().toProjectComment() }
            ?: emptyList()
    }

    /**
     * Executes a JQL query and returns the matching issues.
     * This is a Jira-specific method not part of the ProjectManagementApi interface.
     *
     * @param jql The JQL query string
     * @return List of matching issues
     */
    suspend fun runJql(jql: String): List<ProjectIssue> =
        runJqlPaginated(jql) { it.toProjectIssue() }

    override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql("key in (${issueKeys.joinToString(",")})")
    }

    override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql(issueKeys.joinToString(" OR ") { key -> "issuekey in portfolioChildIssuesOf(\"$key\")" })
    }

    override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> {
        return runJql("parent = $parentKey")
    }

    override suspend fun getIssuesResolved(userId: String, startDate: Instant, endDate: Instant): List<ProjectIssue> =
        runJql("${buildResolvedJql(userId, startDate, endDate)} ORDER BY resolutiondate DESC")

    override suspend fun getIssuesResolvedCount(userId: String, startDate: Instant, endDate: Instant): UInt {
        val jql = buildResolvedJql(userId, startDate, endDate)
        val url = "https://${config.domain}/rest/api/3/search/approximate-count"
        val response = client.post(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(mapOf("jql" to jql))
        }.also { it.ensureSuccess("get issues resolved count for jql=$jql") }
        val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return (root["count"]?.jsonPrimitive?.int ?: 0).toUInt()
    }

    override suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> {
        if (labelCustomFieldId != null && !filter.labels.isNullOrEmpty()) {
            val customFieldFilter = CustomFieldFilter(
                fieldId = labelCustomFieldId,
                values = filter.labels,
            )
            return getIssuesByCustomFieldFilter(
                issueTypes = filter.issueTypes ?: emptyList(),
                customFieldFilter = customFieldFilter,
                resolvedAfter = filter.completedAfter,
                resolvedBefore = filter.completedBefore,
            )
        }

        if (!filter.issueTypes.isNullOrEmpty()) {
            val jql = buildJqlFromFilter(filter)
            return runJql(jql)
        }

        return emptyList()
    }

    override suspend fun getMilestones(projectId: String): List<ProjectMilestone> =
        runJqlPaginated("project = \"$projectId\" AND issuetype = Epic ORDER BY created DESC") { it.toProjectMilestone() }

    override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> {
        return getDirectChildIssues(milestoneId)
    }

    /**
     * Queries issues by custom field filter.
     * This is a Jira-specific method not part of the ProjectManagementApi interface.
     *
     * @param issueTypes List of issue types to filter by
     * @param customFieldFilter The custom field filter
     * @param resolvedAfter Only return issues resolved after this time (optional)
     * @param resolvedBefore Only return issues resolved before this time (optional)
     * @return List of matching issues
     */
    suspend fun getIssuesByCustomFieldFilter(
        issueTypes: List<String>,
        customFieldFilter: CustomFieldFilter,
        resolvedAfter: Instant? = null,
        resolvedBefore: Instant? = null,
    ): List<ProjectIssue> {
        val jql = buildString {
            append("issuetype in (${issueTypes.joinToString(", ")})")
            if (resolvedAfter != null) {
                append(" AND resolutiondate >= \"${resolvedAfter.toUtcDateString()}\"")
            }
            if (resolvedBefore != null) {
                append(" AND resolutiondate <= \"${resolvedBefore.toUtcDateString()}\"")
            }
            append(" AND \"${customFieldFilter.fieldId}\" in (${customFieldFilter.values.joinToString(", ") { "\"$it\"" }})")
            append(" ORDER BY resolutiondate DESC")
        }
        return runJql(jql)
    }

    /**
     * Executes a paginated JQL query and transforms results using the provided mapper.
     */
    private suspend fun <T> runJqlPaginated(jql: String, transform: (Issue) -> T): List<T> = buildList {
        val maxResults = 100
        var startAt = 0
        var nextPageToken: String? = null

        while (true) {
            val url = buildJqlSearchUrl(jql, maxResults, startAt, nextPageToken)
            val response = client.get(url).also { it.ensureSuccess("run JQL: $jql") }
            val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject

            val issues = root["issues"]?.jsonArray ?: break
            issues.mapTo(this) { issue ->
                transform(lenientJson.decodeFromJsonElement(Issue.serializer(), issue.jsonObject))
            }

            // Check pagination termination conditions
            if (root["isLast"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) break

            root["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                nextPageToken = it
                return@let
            } ?: run {
                val total = root["total"]?.jsonPrimitive?.int
                if (total != null) {
                    startAt += maxResults
                    if (startAt >= total) return@buildList
                } else if (issues.size < maxResults) {
                    return@buildList
                } else {
                    throw Exception("JQL results appear truncated (no pagination fields) for jql=$jql")
                }
            }
        }
    }

    private fun buildJqlSearchUrl(jql: String, maxResults: Int, startAt: Int, nextPageToken: String?): String =
        buildString {
            append("https://${config.domain}/rest/api/3/search/jql")
            append("?jql=${jql.encodeURLParameter()}")
            append("&maxResults=$maxResults")
            append("&fields=*all")
            if (nextPageToken != null) {
                append("&nextPageToken=${nextPageToken.encodeURLParameter()}")
            } else {
                append("&startAt=$startAt")
            }
        }

    private fun buildResolvedJql(userId: String, startDate: Instant, endDate: Instant): String =
        "assignee = $userId AND resolutiondate >= \"${startDate.toUtcDateString()}\" AND resolutiondate <= \"${endDate.toUtcDateString()}\""

    private fun buildJqlFromFilter(filter: IssueFilter): String {
        val conditions = mutableListOf<String>()

        if (!filter.issueTypes.isNullOrEmpty()) {
            val types = filter.issueTypes.joinToString(", ") { "\"$it\"" }
            conditions.add("issuetype IN ($types)")
        }

        if (filter.completedAfter != null) {
            conditions.add("resolved >= \"${filter.completedAfter}\"")
        }

        if (filter.completedBefore != null) {
            conditions.add("resolved <= \"${filter.completedBefore}\"")
        }

        return conditions.joinToString(" AND ")
    }
}

private suspend fun HttpResponse.ensureSuccess(operation: String) {
    if (status.value !in 200..299) {
        println("Response: ```${bodyAsText()}```")
        throw Exception("Failed to $operation: HTTP ${status.value}")
    }
}

private fun Instant.toUtcDateString(): String {
    val localDateTime = toLocalDateTime(TimeZone.UTC)
    return "${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute}"
}

/**
 * Converts a Jira Issue to a unified ProjectIssue.
 */
fun Issue.toProjectIssue(): ProjectIssue {
    return ProjectIssue(
        id = id,
        key = key,
        url = htmlUrl,
        title = fields.summary,
        description = fields.description.toPlainText(),
        status = fields.status?.name,
        statusCategory = fields.status?.statusCategory?.toProjectStatusCategory(),
        issueType = fields.issueType?.name,
        priority = fields.priority?.name,
        estimate = fields.customfield_10100, // Story points
        assigneeId = fields.assignee?.accountId,
        assigneeName = fields.assignee?.displayName,
        creatorId = fields.creator?.accountId,
        creatorName = fields.creator?.displayName,
        parentKey = fields.parent?.key,
        createdAt = fields.created,
        updatedAt = fields.updated,
        completedAt = fields.resolutionDate,
        dueDate = fields.dueDate,
    )
}

/**
 * Converts a Jira Comment to a unified ProjectComment.
 */
fun Comment.toProjectComment(): ProjectComment {
    return ProjectComment(
        id = id,
        body = body.toPlainText(),
        authorId = author.accountId,
        authorName = author.displayName,
        createdAt = created,
        updatedAt = updated,
    )
}

/**
 * Converts a Jira Issue (Epic) to a ProjectMilestone.
 */
fun Issue.toProjectMilestone(): ProjectMilestone {
    return ProjectMilestone(
        id = key,
        name = fields.summary,
        description = fields.description.toPlainText(),
        targetDate = fields.dueDate,
        progress = null, // Jira doesn't have native progress on Epics
        status = fields.status?.name,
        projectId = fields.project?.key,
    )
}

/**
 * Converts a Jira StatusCategory to the unified StatusCategory.
 */
private fun StatusCategory.toProjectStatusCategory(): UnifiedStatusCategory? {
    return when (this.key?.lowercase()) {
        "new", "undefined" -> UnifiedStatusCategory.TODO
        "indeterminate" -> UnifiedStatusCategory.IN_PROGRESS
        "done" -> UnifiedStatusCategory.DONE
        else -> null
    }
}
