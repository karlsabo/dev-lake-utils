package com.github.karlsabo.github

import com.github.karlsabo.http.installHttpRetry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
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
        json.decodeFromString<GitHubConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        json.decodeFromString<GitHubSecret>(source.readText())
    }

    return GitHubApiRestConfig(
        secretConfig.githubToken,
    )
}

/**
 * Saves GitHub configuration to a file.
 */
@Suppress("unused")
fun saveGitHubConfig(config: GitHubConfig, configPath: Path) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(json.encodeToString(GitHubConfig.serializer(), config))
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
            json(Json { ignoreUnknownKeys = true })
        }
        installHttpRetry()
        expectSuccess = false
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> {
        val formattedStartDate = startDate.toUtcDateString()
        val formattedEndDate = endDate.toUtcDateString()

        val query =
            "author:$gitHubUserId ${organizationIds.joinToString(" ") { "org:$it" }} is:pr is:merged merged:$formattedStartDate..$formattedEndDate"
        val encodedQuery = query.encodeURLParameter()

        val pullRequests = mutableListOf<Issue>()
        var page = 1
        val perPage = 20
        var totalCount = 1000

        while (page <= (totalCount / perPage) + 1) {
            val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=$perPage&page=$page"

            val response = client.get(url) {
                addGitHubHeaders()
            }

            val responseText = response.bodyAsText()
            val root = Json.parseToJsonElement(responseText).jsonObject

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

    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val formattedStartDate = startDate.toUtcDateString()
        val formattedEndDate = endDate.toUtcDateString()

        val query =
            "author:$gitHubUserId ${organizationIds.joinToString(" ") { "org:$it" }} is:pr is:merged merged:$formattedStartDate..$formattedEndDate"
        val encodedQuery = query.encodeURLParameter()

        val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=1"

        val response = client.get(url) {
            addGitHubHeaders()
        }

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("$gitHubUserId query=`$query`")
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to get merged pull requests count: ${response.status.value} for $gitHubUserId")
        }
        val root = Json.parseToJsonElement(responseText).jsonObject

        val totalCount = root["total_count"]?.jsonPrimitive?.int ?: 0

        return totalCount.toUInt()
    }
}

fun JsonElement.toPullRequest(): Issue {
    return json.decodeFromJsonElement(Issue.serializer(), this)
}

private fun HttpRequestBuilder.addGitHubHeaders() {
    header("Accept", "application/vnd.github.v3+json")
    header("X-GitHub-Api-Version", "2022-11-28")
}

private fun Instant.toUtcDateString(): String {
    return toLocalDateTime(TimeZone.UTC).toString()
}
