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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

    @Serializable
    private data class CreateReviewRequest(val event: String, val body: String? = null)

    @Serializable
    private data class PullRequestReview(
        val id: Long? = null,
        val state: String? = null,
        val user: ReviewUser? = null,
    )

    @Serializable
    private data class ReviewUser(
        val login: String? = null,
        val type: String? = null,
    )

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
        val encodedQuery = createMergedPrEncodedQuery(startDate, endDate, gitHubUserId, organizationIds)
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

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val encodedQuery = createReviewedPrEncodedQuery(startDate, endDate, gitHubUserId, organizationIds)
        val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=1"

        val response = client.get(url)

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to get pull request review count: ${response.status.value} for $gitHubUserId")
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

    /**
     * Lists notifications for the authenticated user.
     * Note: By default, GitHub returns only UNREAD notifications. To also include notifications you have
     * already viewed (read) but not marked as done/archive, request with `all=true`.
     * This method intentionally sets `all=true` so callers can see both unread and previously-read items.
     */
    override suspend fun listNotifications(): List<Notification> {
        // karlfixme need to filter 'done' and paginate all=true&
        val url = "https://api.github.com/notifications?participating=false"
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to list notifications: ${response.status.value}")
        }
        val notifications = lenientJson.decodeFromString<List<Notification>>(responseText)
        return notifications
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

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        val reviewsUrl = if (url.endsWith("/reviews")) url else "$url/reviews"
        val response = client.post(reviewsUrl) {
            contentType(ContentType.Application.Json)
            setBody(CreateReviewRequest(event = "APPROVE", body = body))
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to approve pull request: ${response.status.value} for url=$url")
        }
    }

    /**
     * Returns true if the pull request already has any APPROVED review from a human reviewer.
     * Bot approvals (e.g., users of type "Bot", accounts ending with "[bot]",
     * or known automation accounts like "continuous-deployer") are ignored.
     */
    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        val reviewsUrl = if (url.endsWith("/reviews")) url else "$url/reviews"
        val response = client.get(reviewsUrl)
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            println("\tresponseText=```$responseText```")
            throw Exception("Failed to list pull request reviews: ${response.status.value} for url=$url")
        }

        return try {
            val elements = lenientJson.parseToJsonElement(responseText).jsonArray
            elements.any { el ->
                val obj = el.jsonObject
                val state = obj["state"]?.jsonPrimitive?.content
                if (!state.equals("APPROVED", ignoreCase = true)) return@any false

                val userObj = obj["user"]?.jsonObject
                val login = userObj?.get("login")?.jsonPrimitive?.content
                val type = userObj?.get("type")?.jsonPrimitive?.content
                !isBotUser(login, type)
            }
        } catch (error: Exception) {
            println("Failed to parse GitHub JSON $responseText")
            error.printStackTrace()
            try {
                val reviews = lenientJson.decodeFromString<List<PullRequestReview>>(responseText)
                reviews.any { review ->
                    review.state.equals("APPROVED", ignoreCase = true) &&
                            !isBotUser(review.user?.login, review.user?.type)
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isBotUser(login: String?, type: String?): Boolean {
        if (type.equals("Bot", ignoreCase = true)) return true
        val normalizedLogin = login?.lowercase() ?: return false
        if (normalizedLogin.endsWith("[bot]")) return true
        // Known automation accounts to ignore for approvals
        val excluded = setOf(
            "continuous-deployer",
        )
        return normalizedLogin in excluded
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId"
        val response = client.delete(url)
        val responseText = response.bodyAsText()
        println("\tresponseText=```$responseText```")
        if (response.status.value !in 200..299) {
            throw Exception("Failed to mark notification as done: ${response.status.value} for threadId=$threadId")
        }
    }

    @Serializable
    private data class ThreadSubscriptionUpdate(val ignored: Boolean)

    override suspend fun unsubscribeFromNotification(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId/subscription"
        val response = client.put(url) {
            contentType(ContentType.Application.Json)
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

private fun createReviewedPrEncodedQuery(
    startDate: Instant,
    endDate: Instant,
    gitHubUserId: String,
    organizationIds: List<String>,
): String {
    val formattedStartDate = startDate.toUtcDateString()
    val formattedEndDate = endDate.toUtcDateString()

    // q=reviewed-by:karlsabo (org:klaviyo OR org:zitadel) is:pr updated:2025-01-01..2025-12-31
    val query =
        "reviewed-by:$gitHubUserId ${organizationIds.joinToString(" ") { "org:$it" }} is:pr updated:$formattedStartDate..$formattedEndDate"
    val encodedQuery = query.encodeURLParameter()
    return encodedQuery
}

fun JsonElement.toPullRequest(): Issue {
    return lenientJson.decodeFromJsonElement(Issue.serializer(), this)
}

private fun Instant.toUtcDateString(): String {
    return toLocalDateTime(TimeZone.UTC).toString()
}
