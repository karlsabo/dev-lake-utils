package com.github.karlsabo.github

import com.github.karlsabo.common.datetime.DateTimeFormatting.toIsoUtcDateTime
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

internal class GitHubPullRequestSearchRestApi(
    private val restClient: GitHubRestClient,
) : GitHubPullRequestSearchApi {
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
        return issueSearchCount(encodedQuery, "get merged pull requests count", "for $gitHubUserId")
    }

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        val encodedQuery = createReviewedPrEncodedQuery(startDate, endDate, gitHubUserId, organizationIds)
        return issueSearchCount(encodedQuery, "get pull request review count", "for $gitHubUserId")
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        val query = "author:$author ${organizationQualifier(organizationIds)} is:pr is:open"
        return paginatedIssueQuery(query.encodeURLParameter())
    }

    private suspend fun issueSearchCount(
        encodedQuery: String,
        operation: String,
        context: String,
    ): UInt {
        val url = "https://api.github.com/search/issues?q=$encodedQuery&per_page=1"
        val response = restClient.client.get(url)
        val responseText = response.bodyAsText()

        if (response.status.value !in successStatusCodes) {
            logger.error { "$operation responseText=```$responseText```" }
            throwGitHubApiException(
                operation = operation,
                statusCode = response.status.value,
                context = context,
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
            val response = restClient.client.get(url)
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
}
