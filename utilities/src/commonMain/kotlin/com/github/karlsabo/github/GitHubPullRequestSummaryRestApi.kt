package com.github.karlsabo.github

import com.github.karlsabo.tools.lenientJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class GitHubPullRequestSummaryRestApi(
    private val restClient: GitHubRestClient,
) : GitHubPullRequestSummaryApi {
    override suspend fun getCheckRunsForRef(
        owner: String,
        repo: String,
        ref: String,
    ): CheckRunSummary {
        val checkRunsText = restClient.getSuccessfulResponseText(
            url = "https://api.github.com/repos/$owner/$repo/commits/$ref/check-runs?per_page=$COMMIT_STATUS_PER_PAGE",
            operation = "get check runs",
            context = "for $owner/$repo ref=$ref",
        )
        val statusContextsText = restClient.getSuccessfulResponseText(
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
        val reviewsText = restClient.getSuccessfulResponseText(
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

    private suspend fun getRequestedReviewCount(
        owner: String,
        repo: String,
        prNumber: Int,
    ): Int {
        val requestedUrl = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber/requested_reviewers"
        val requestedResponse = restClient.client.get(requestedUrl)
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
}
