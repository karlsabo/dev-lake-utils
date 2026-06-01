package com.github.karlsabo.github

import com.github.karlsabo.common.datetime.DateTimeFormatting.toIsoUtcDateTime
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Implementation of the GitHubApi interface using REST.
 */
@Suppress("TooManyFunctions")
class GitHubRestApi(
    private val config: GitHubApiRestConfig,
) : GitHubApi {
    constructor(config: GitHubApiRestConfig, httpClient: HttpClient) : this(config) {
        clientOverride = httpClient
    }

    private var clientOverride: HttpClient? = null

    private val client: HttpClient by lazy {
        clientOverride ?: HttpClient(CIO) {
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
                header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            installHttpRetry()
            install(HttpCache)
            expectSuccess = false
        }
    }

    @Serializable
    private data class CreateReviewRequest(
        val event: String,
        val body: String = "",
    )

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
        val formattedStartDate = startDateInclusive.toIsoUtcDateTime()
        val formattedEndDate = endDateInclusive.toIsoUtcDateTime()
        val organizationQuery = organizationQualifier(organizationIds)
        val query = buildString {
            append("$organizationQuery is:merged")
            append(" merged:$formattedStartDate..$formattedEndDate")
            append(" is:pr $searchText in:title,body")
        }

        return paginatedIssueQuery(query.encodeURLParameter())
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

        if (response.status.value !in successStatusCodes) {
            logger.error { "getMergedPullRequestCount responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "get merged pull requests count",
                statusCode = response.status.value,
                context = "for $gitHubUserId",
                responseText = responseText,
            )
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

        if (response.status.value !in successStatusCodes) {
            logger.error { "getPullRequestReviewCount responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "get pull request review count",
                statusCode = response.status.value,
                context = "for $gitHubUserId",
                responseText = responseText,
            )
        }

        val root = Json.parseToJsonElement(responseText).jsonObject
        val totalCount = root["total_count"]?.jsonPrimitive?.int ?: 0
        return totalCount.toUInt()
    }

    private suspend fun paginatedIssueQuery(encodedQuery: String): MutableList<Issue> {
        val pullRequests = mutableListOf<Issue>()
        var page = 1
        var totalCount = GITHUB_SEARCH_RESULT_LIMIT
        var hasMoreResults = true

        while (hasMoreResults && page <= pageLimit(totalCount)) {
            val url = searchIssuesUrl(encodedQuery, page)
            val response = client.get(url)
            val responseText = response.bodyAsText()

            if (response.status.value !in successStatusCodes) {
                logger.error { "searchPullRequestsByText query failed responseText=```$responseText```" }
                throwGitHubApiException(
                    operation = "search pull requests",
                    statusCode = response.status.value,
                    responseText = responseText,
                )
            }

            val root = lenientJson.parseToJsonElement(responseText).jsonObject
            val items = root["items"]?.jsonArray.orEmpty()
            hasMoreResults = items.isNotEmpty()

            if (hasMoreResults) {
                pullRequests.addAll(items.map { it.toPullRequest() })
                page++
                totalCount = root["total_count"]?.jsonPrimitive?.int ?: 0
            }
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
        val notifications = mutableListOf<Notification>()
        var page = 1
        var hasMoreResults = true

        while (hasMoreResults) {
            val url = notificationsUrl(page)
            val response = client.get(url) {
                header(HttpHeaders.CacheControl, "no-cache")
            }
            val responseText = response.bodyAsText()

            if (response.status.value !in successStatusCodes) {
                logger.error {
                    "Failed to list notifications $url response.status=${response.status} " +
                        "responseText=```$responseText```"
                }
                throwGitHubApiException(
                    operation = "list notifications",
                    statusCode = response.status.value,
                    responseText = responseText,
                )
            }

            val pageNotifications = lenientJson.decodeFromString<List<Notification>>(responseText)
            hasMoreResults = pageNotifications.isNotEmpty()

            if (hasMoreResults) {
                notifications.addAll(pageNotifications)
                page++
            }
        }

        return notifications
    }

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error {
                "Failed to get pull request $url response.status=${response.status} responseText=```$responseText```"
            }
            throwGitHubApiException(
                operation = "get pull request",
                statusCode = response.status.value,
                context = "for url=$url",
                responseText = responseText,
            )
        }
        return lenientJson.decodeFromString(PullRequest.serializer(), responseText)
    }

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        val reviewsUrl = reviewsUrl(url)
        val response = client.post(reviewsUrl) {
            contentType(ContentType.Application.Json)
            setBody(CreateReviewRequest(event = "APPROVE", body = body ?: ""))
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error { "approvePullRequest responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "approve pull request",
                statusCode = response.status.value,
                context = "for url=$url",
                responseText = responseText,
            )
        }
    }

    /**
     * Returns true if the pull request already has any APPROVED review from a human reviewer.
     * Bot approvals (e.g., users of type "Bot", accounts ending with "[bot]",
     * or known automation accounts like "continuous-deployer") are ignored.
     */
    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        val response = client.get(reviewsUrl(url))
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error { "hasAnyApprovedReview responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "list pull request reviews",
                statusCode = response.status.value,
                context = "for url=$url",
                responseText = responseText,
            )
        }

        return hasApprovedHumanReview(responseText)
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId"
        val response = client.delete(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error {
                "Failed to mark notification done $url response.status=${response.status} " +
                    "responseText=```$responseText```"
            }
            throwGitHubApiException(
                operation = "mark notification as done",
                statusCode = response.status.value,
                context = "for threadId=$threadId",
                responseText = responseText,
            )
        }
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId/subscription"
        val response = client.delete(url)
        if (response.status.value !in listOf(HTTP_NO_CONTENT, HTTP_NOT_FOUND)) {
            val responseText = response.bodyAsText()
            logger.error { "unsubscribeFromNotification responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "unsubscribe from notification",
                statusCode = response.status.value,
                context = "for threadId=$threadId",
                responseText = responseText,
            )
        }
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        val query = "author:$author ${organizationQualifier(organizationIds)} is:pr is:open"
        return paginatedIssueQuery(query.encodeURLParameter())
    }

    override suspend fun getCheckRunsForRef(
        owner: String,
        repo: String,
        ref: String,
    ): CheckRunSummary {
        val checkRunsText = getSuccessfulResponseText(
            url = "https://api.github.com/repos/$owner/$repo/commits/$ref/check-runs?per_page=$COMMIT_STATUS_PER_PAGE",
            operation = "get check runs",
            context = "for $owner/$repo ref=$ref",
        )
        val statusContextsText = getSuccessfulResponseText(
            url = "https://api.github.com/repos/$owner/$repo/commits/$ref/status?per_page=$COMMIT_STATUS_PER_PAGE",
            operation = "get commit statuses",
            context = "for $owner/$repo ref=$ref",
        )

        val checkRunCounts = parseCheckRunCounts(
            lenientJson.parseToJsonElement(checkRunsText).jsonObject["check_runs"]?.jsonArray.orEmpty(),
        )
        val statusContextCounts = parseCommitStatusCounts(
            lenientJson.parseToJsonElement(statusContextsText).jsonObject["statuses"]?.jsonArray.orEmpty(),
        )

        return (checkRunCounts + statusContextCounts).toSummary()
    }

    override suspend fun getReviewSummary(
        owner: String,
        repo: String,
        prNumber: Int,
    ): ReviewSummary {
        val reviewsText = getSuccessfulResponseText(
            url = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber/reviews?per_page=$COMMIT_STATUS_PER_PAGE",
            operation = "get reviews",
            context = "for $owner/$repo#$prNumber",
        )
        val requestedCount = getRequestedReviewCount(owner, repo, prNumber)
        val reviews = latestHumanReviews(reviewsText).map { (user, state) -> ReviewState(user, state) }
        val approvedCount = reviews.count { it.state == ReviewStateValue.APPROVED }
        val pendingReviewCount = reviews.count(::isPendingReviewState)
        val totalRequested = approvedCount + requestedCount + pendingReviewCount

        return ReviewSummary(
            approvedCount = approvedCount,
            requestedCount = totalRequested,
            reviews = reviews,
        )
    }

    override suspend fun submitReview(
        prApiUrl: String,
        event: ReviewStateValue,
        reviewComment: String?,
    ) {
        val response = client.post(reviewsUrl(prApiUrl)) {
            contentType(ContentType.Application.Json)
            setBody(CreateReviewRequest(event = event.toSubmitEventString(), body = reviewComment ?: ""))
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error { "submitReview responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "submit review",
                statusCode = response.status.value,
                context = "for url=$prApiUrl",
                responseText = responseText,
            )
        }
    }

    private suspend fun getSuccessfulResponseText(
        url: String,
        operation: String,
        context: String,
    ): String {
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error {
                "Failed to $operation $context response.status=${response.status} responseText=```$responseText```"
            }
            throwGitHubApiException(operation, response.status.value, context, responseText)
        }
        return responseText
    }

    private suspend fun getRequestedReviewCount(
        owner: String,
        repo: String,
        prNumber: Int,
    ): Int {
        val requestedUrl = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber/requested_reviewers"
        val requestedResponse = client.get(requestedUrl)
        val requestedText = requestedResponse.bodyAsText()
        return if (requestedResponse.status.value in successStatusCodes) {
            val requestedRoot = lenientJson.parseToJsonElement(requestedText).jsonObject
            val users = requestedRoot["users"]?.jsonArray
            val teams = requestedRoot["teams"]?.jsonArray
            (users?.size ?: 0) + (teams?.size ?: 0)
        } else {
            0
        }
    }

    private fun hasApprovedHumanReview(responseText: String): Boolean = try {
        lenientJson.parseToJsonElement(responseText).jsonArray.any(::isApprovedHumanReview)
    } catch (error: SerializationException) {
        logger.error(error) { "Failed to parse GitHub JSON $responseText" }
        decodeApprovedHumanReview(responseText)
    }

    private fun decodeApprovedHumanReview(responseText: String): Boolean = try {
        lenientJson.decodeFromString<List<PullRequestReview>>(responseText).any { review ->
            review.state.equals("APPROVED", ignoreCase = true) &&
                !isBotUser(review.user?.login, review.user?.type)
        }
    } catch (_: SerializationException) {
        false
    }
}
