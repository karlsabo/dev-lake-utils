package com.github.karlsabo.jira

import com.github.karlsabo.Credentials
import com.github.karlsabo.http.installHttpRetry
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

class JiraRestApi(private val config: JiraApiRestConfig) : JiraApi {
    private val client: HttpClient = HttpClient(CIO) {
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

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment> {
        val commentList = mutableListOf<Comment>()
        val url =
            "https://${config.domain}/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=1&startAt=0"
        val response = client.get(url)
        if (response.status.value !in 200..299) {
            println("Response: ```${response.bodyAsText()}```")
            throw Exception("Failed to get comments: ${response.status.value} for issueKey=$issueKey")
        }
        val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val comments = root["comments"]?.jsonArray ?: return commentList
        commentList += comments.map { it.jsonObject.toComment() }

        return commentList
    }

    override suspend fun runJql(jql: String): List<Issue> {
        val issueList = mutableListOf<Issue>()
        var startAt = 0
        val maxResults = 100

        while (true) {
            val encodedJql = jql.encodeURLParameter()
            val url =
                "https://${config.domain}/rest/api/3/search/jql?jql=$encodedJql&startAt=$startAt&maxResults=$maxResults&fields=*all"

            val response = client.get(url)
            if (response.status.value !in 200..299) {
                println("Response: ```${response.bodyAsText()}```")
                throw Exception("Failed to run JQL: ${response.status.value} for jql=$jql")
            }
            val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject

            val issues = root["issues"]?.jsonArray ?: break
            issueList += issues.map { issue -> lenientJson.decodeFromJsonElement(Issue.serializer(), issue.jsonObject) }

            val total = root["total"]?.jsonPrimitive?.int ?: break
            startAt += maxResults

            if (startAt >= total) break
        }

        return issueList
    }

    override suspend fun getIssues(issueKeys: List<String>): List<Issue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql("key in (${issueKeys.joinToString(",")})")
    }

    override suspend fun getChildIssues(issueKeys: List<String>): List<Issue> {
        if (issueKeys.isEmpty()) return emptyList()
        return runJql(issueKeys.joinToString(" OR ") { key -> "issuekey in portfolioChildIssuesOf(\"$key\")" })
    }

    override suspend fun getIssuesResolved(userJiraId: String, startDate: Instant, endDate: Instant): List<Issue> {
        val jql =
            "assignee = $userJiraId AND resolutiondate >= \"${startDate.toUtcDateString()}\" AND resolutiondate <= \"${endDate.toUtcDateString()}\" ORDER BY resolutiondate DESC"
        return runJql(jql)
    }

    override suspend fun getIssuesResolvedCount(userJiraId: String, startDate: Instant, endDate: Instant): UInt {
        val jql =
            "assignee = $userJiraId AND resolutiondate >= \"${startDate.toUtcDateString()}\" AND resolutiondate <= \"${endDate.toUtcDateString()}\""
        val url = "https://${config.domain}/rest/api/3/search/approximate-count"
        val response = client.post(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(mapOf("jql" to jql))
        }
        if (response.status.value !in 200..299) {
            println("Response: ```${response.bodyAsText()}```")
            throw Exception("Failed to get issues resolved count: ${response.status.value} for jql=$jql")
        }
        val root = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return (root["count"]?.jsonPrimitive?.int ?: 0).toUInt()
    }
}

private fun Instant.toUtcDateString(): String {
    val toLocalDateTime = toLocalDateTime(TimeZone.UTC)
    return "${toLocalDateTime.date} ${toLocalDateTime.hour}:${toLocalDateTime.minute}"
}
