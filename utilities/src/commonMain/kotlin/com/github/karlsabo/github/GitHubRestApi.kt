package com.github.karlsabo.github

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
        expectSuccess = false
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<GitHubPullRequest> {
        val formattedStartDate = toUtcDate(startDate)
        val formattedEndDate = toUtcDate(endDate)

        val query =
            "author:$gitHubUserId ${organizationIds.joinToString(" ") { "org:$it" }} is:pr is:merged merged:$formattedStartDate..$formattedEndDate"
        val encodedQuery = query.encodeURLParameter()

        val pullRequests = mutableListOf<GitHubPullRequest>()
        var page = 1
        val perPage = 100
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
                val prUrl = item.jsonObject["pull_request"]?.jsonObject?.get("url")?.jsonPrimitive?.content ?: continue
                val prResponse = client.get(prUrl) {
                    addGitHubHeaders()
                }
                val prResponseText = prResponse.bodyAsText()
                val pullRequestJson = Json.parseToJsonElement(prResponseText).jsonObject
                pullRequests.add(pullRequestJson.toPullRequest())
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
        val formattedStartDate = toUtcDate(startDate)
        val formattedEndDate = toUtcDate(endDate)

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

private fun JsonObject.toPullRequest(): GitHubPullRequest {
    val id = this["id"]?.jsonPrimitive?.content ?: ""
    val number = this["number"]?.jsonPrimitive?.int ?: 0
    val title = this["title"]?.jsonPrimitive?.content ?: ""
    val url = this["url"]?.jsonPrimitive?.content ?: ""
    val htmlUrl = this["html_url"]?.jsonPrimitive?.content ?: ""
    val state = this["state"]?.jsonPrimitive?.content ?: ""

    val createdAt = this["created_at"]?.jsonPrimitive?.content?.let { Instant.parse(it) }
        ?: Instant.fromEpochSeconds(0)
    val updatedAt = this["updated_at"]?.jsonPrimitive?.content?.let { Instant.parse(it) }
        ?: Instant.fromEpochSeconds(0)
    val closedAt = this["closed_at"]?.jsonPrimitive?.content?.let { Instant.parse(it) }
    val mergedAt = this["merged_at"]?.jsonPrimitive?.content?.let { Instant.parse(it) }

    val userJson = this["user"]?.jsonObject ?: JsonObject(emptyMap())
    val user = GitHubUser(
        id = userJson["id"]?.jsonPrimitive?.content ?: "",
        login = userJson["login"]?.jsonPrimitive?.content ?: "",
        avatarUrl = userJson["avatar_url"]?.jsonPrimitive?.content,
        url = userJson["url"]?.jsonPrimitive?.content ?: ""
    )

    val body = this["body"]?.jsonPrimitive?.content
    val additions = this["additions"]?.jsonPrimitive?.int
    val deletions = this["deletions"]?.jsonPrimitive?.int
    val changedFiles = this["changed_files"]?.jsonPrimitive?.int

    val repoJson = this["base"]?.jsonObject?.get("repo")?.jsonObject ?: JsonObject(emptyMap())
    val repository = GitHubRepository(
        id = repoJson["id"]?.jsonPrimitive?.content ?: "",
        name = repoJson["name"]?.jsonPrimitive?.content ?: "",
        fullName = repoJson["full_name"]?.jsonPrimitive?.content ?: "",
        url = repoJson["url"]?.jsonPrimitive?.content ?: "",
        htmlUrl = repoJson["html_url"]?.jsonPrimitive?.content ?: ""
    )

    return GitHubPullRequest(
        id = id,
        number = number,
        title = title,
        url = url,
        htmlUrl = htmlUrl,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        closedAt = closedAt,
        mergedAt = mergedAt,
        user = user,
        body = body,
        additions = additions,
        deletions = deletions,
        changedFiles = changedFiles,
        repository = repository
    )
}


private fun HttpRequestBuilder.addGitHubHeaders() {
    header("Accept", "application/vnd.github.v3+json")
    header("X-GitHub-Api-Version", "2022-11-28")
}

private fun toUtcDate(instant: Instant): String {
    return instant.toLocalDateTime(TimeZone.UTC).toString()
}
