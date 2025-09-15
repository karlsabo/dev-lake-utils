package com.github.karlsabo.github

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Configuration for GitHub REST API.
 */
data class GitHubApiRestConfig(
    val token: String,

    ) {
    override fun toString(): String {
        return "GitHubApiRestConfig()"
    }
}

/**
 * Configuration for GitHub API.
 */
@Serializable
data class GitHubConfig(
    val tokenPath: String,
)

/**
 * Secret configuration for GitHub API.
 */
@Serializable
data class GitHubSecret(
    val githubToken: String,
)

/**
 * Loads GitHub configuration from a file.
 */
fun loadGitHubConfig(configFilePath: Path): GitHubApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubSecret>(source.readText())
    }

    return GitHubApiRestConfig(
        secretConfig.githubToken,
    )
}

/**
 * Saves GitHub configuration to a file.
 */
@Suppress("unused")
fun saveGitHubConfig(configPath: Path, config: GitHubConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(GitHubConfig.serializer(), config))
    }
}

/**
 * Implementation of the GitHubApi interface using REST.
 */
class GitHubRestApi(private val config: GitHubApiRestConfig) : GitHubApi {
    private val client: HttpClient = HttpClient(CIO) {
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(config.token, "")
                }
                sendWithoutRequest { true }
            }
        }
        install(ContentNegotiation) {
            json(lenientJson)
        }
        defaultRequest {
            header("Accept", "application/vnd.github.v3+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        installHttpRetry()
        install(HttpCache)
        expectSuccess = false
    }

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> {
        val formattedStartDate = startDateInclusive.toUtcDateString()
        val formattedEndDate = endDateInclusive.toUtcDateString()

        val query =
            "${organizationIds.joinToString(" ") { "org:$it" }} is:merged merged:$formattedStartDate..$formattedEndDate is:pr $searchText in:title,body"
        val encodedQuery = query.encodeURLParameter()

        return paginatedIssueQuery(encodedQuery)
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> {
        val encodedQuery = createMergedPrEncodedQuery(startDate, endDate, gitHubUserId, organizationIds)
        return paginatedIssueQuery(encodedQuery)
    }

    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val encodedQuery =
            createMergedPrEncodedQuery(startDate, endDate, gitHubUserId, organizationIds)
        val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=1"

        val response = client.get(url)

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to get merged pull requests count: ${response.status.value} for $gitHubUserId")
        }
        val root = Json.parseToJsonElement(responseText).jsonObject

        val totalCount = root["total_count"]?.jsonPrimitive?.int ?: 0

        return totalCount.toUInt()
    }

    private suspend fun paginatedIssueQuery(encodedQuery: String): MutableList<Issue> {
        val pullRequests = mutableListOf<Issue>()
        var page = 1
        val perPage = 20
        var totalCount = 1000

        while (page <= (totalCount / perPage) + 1) {
            val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=$perPage&page=$page"

            val response = client.get(url)

            val responseText = response.bodyAsText()
            if (response.status.value !in 200..299) {
                println("searchPullRequestsByText query=`$`")
                println("\tresponseText=```$responseText```")
                throw Exception("Failed to search pull requests: ${response.status.value}")
            }

            val root = lenientJson.parseToJsonElement(responseText).jsonObject

            val items = root["items"]?.jsonArray ?: break
            if (items.isEmpty()) break

            for (item in items) {
                pullRequests.add(item.toPullRequest())
            }

            page++
            totalCount = root["total_count"]?.jsonPrimitive?.int ?: 0
        }

        return pullRequests
    }

    override suspend fun listNotifications(): List<Notification> {
        val url = "https://api.github.com/notifications"
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to list notifications: ${response.status.value}")
        }
        return lenientJson.decodeFromString(responseText)
    }

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to get pull request: ${response.status.value} for url=$url")
        }
        return lenientJson.decodeFromString(PullRequest.serializer(), responseText)
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId"
        val response = client.patch(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to mark notification as done: ${response.status.value} for threadId=$threadId")
        }
    }

    @Serializable
    private data class ThreadSubscriptionUpdate(val ignored: Boolean)

    override suspend fun unsubscribeFromNotification(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId/subscription"
        val response = client.put(url) {
            setBody(ThreadSubscriptionUpdate(ignored = true))
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to unsubscribe from notification: ${response.status.value} for threadId=$threadId")
        }
    }
}

private fun createMergedPrEncodedQuery(
    startDate: Instant,
    endDate: Instant,
    gitHubUserId: String,
    organizationIds: List<String>,
): String {
    val formattedStartDate = startDate.toUtcDateString()
    val formattedEndDate = endDate.toUtcDateString()

    val query =
        "author:$gitHubUserId ${organizationIds.joinToString(" ") { "org:$it" }} is:pr is:merged merged:$formattedStartDate..$formattedEndDate"
    val encodedQuery = query.encodeURLParameter()
    return encodedQuery
}

fun JsonElement.toPullRequest(): Issue {
    return lenientJson.decodeFromJsonElement(Issue.serializer(), this)
}

private fun Instant.toUtcDateString(): String {
    return toLocalDateTime(TimeZone.UTC).toString()
}
