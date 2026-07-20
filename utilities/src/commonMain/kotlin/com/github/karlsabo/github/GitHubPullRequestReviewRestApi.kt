package com.github.karlsabo.github

import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray

private val logger = KotlinLogging.logger {}

internal class GitHubPullRequestReviewRestApi(
    private val restClient: GitHubRestClient,
) : GitHubPullRequestReviewApi {
    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        val response = restClient.client.get(url)
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
        val response = restClient.client.post(reviewsUrl) {
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

    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        val response = restClient.client.get(reviewsUrl(url))
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
