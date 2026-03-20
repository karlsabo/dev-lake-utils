package com.github.karlsabo.github

import com.github.karlsabo.github.config.GitHubApiRestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubRestApiReviewSummaryTest {
    @Test
    fun getReviewSummary_countsRequestedTeamsAsPendingReviewers() = runBlocking {
        val client =
            HttpClient(
                MockEngine { request ->
                    val body =
                        when (request.url.encodedPath) {
                            "/repos/test-org/test-repo/pulls/25843/reviews" -> "[]"
                            "/repos/test-org/test-repo/pulls/25843/requested_reviewers" ->
                                """
                                {
                                  "users": [],
                                  "teams": [
                                    { "id": 1, "name": "AppSec", "slug": "appsec" }
                                  ]
                                }
                                """.trimIndent()

                            else -> error("Unexpected request path: ${request.url.encodedPath}")
                        }

                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )

        val api = GitHubRestApi(GitHubApiRestConfig(token = "test-token"), client)

        val summary = api.getReviewSummary(owner = "test-org", repo = "test-repo", prNumber = 25843)

        assertEquals(0, summary.approvedCount)
        assertEquals(1, summary.requestedCount)
        assertEquals(emptyList(), summary.reviews)
    }
}
