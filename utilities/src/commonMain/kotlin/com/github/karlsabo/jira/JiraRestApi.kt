package com.github.karlsabo.jira

import com.github.karlsabo.Credentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.*

private val json = Json { ignoreUnknownKeys = true }

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
        json.decodeFromString<JiraConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.apiKeyPath)).buffered().use { source ->
        json.decodeFromString<JiraSecret>(source.readText())
    }

    return JiraApiRestConfig(
        Credentials(
            config.username,
            secretConfig.jiraApiKey,
        ),
        config.domain,
    )
}

fun saveJiraConfig(config: JiraConfig, configPath: Path) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(json.encodeToString(JiraConfig.serializer(), config))
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
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment> {
        val commentList = mutableListOf<Comment>()
        val url = "https://${config.domain}/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=$maxResults"
        val response = client.get(url)
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
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
                "https://${config.domain}/rest/api/3/search?jql=$encodedJql&startAt=$startAt&maxResults=$maxResults"

            val response = client.get(url)
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            val issues = root["issues"]?.jsonArray ?: break
            issueList += issues.map { it.jsonObject.toIssue() }

            val total = root["total"]?.jsonPrimitive?.int ?: break
            startAt += maxResults

            if (startAt >= total) break
        }

        return issueList
    }

    override suspend fun getIssuesResolved(userJiraId: String, startDate: Instant, endDate: Instant): List<Issue> {
        val jql =
            "assignee = $userJiraId AND resolutiondate >= \"${startDate.toUtcDateString()}\" AND resolutiondate <= \"${endDate.toUtcDateString()}\" ORDER BY resolutiondate DESC"
        return runJql(jql)
    }
}

private fun Instant.toUtcDateString(): String {
    return toLocalDateTime(TimeZone.UTC).date.toString()
}
