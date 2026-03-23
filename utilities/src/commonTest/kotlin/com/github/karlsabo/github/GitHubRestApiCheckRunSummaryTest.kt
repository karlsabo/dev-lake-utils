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

class GitHubRestApiCheckRunSummaryTest {
    @Test
    fun getCheckRunsForRef_countsCheckRunsAndCommitStatusContexts() = runBlocking {
        val api = createApi(
            checkRunsBody = checkRunsResponse(List(10) {
                CheckRunPayload(
                    status = "completed",
                    conclusion = "success"
                )
            }),
            statusBody = statusResponse(listOf("success")),
        )

        val summary = api.getCheckRunsForRef(owner = "test-org", repo = "test-repo", ref = "abc123")

        assertEquals(
            CheckRunSummary(total = 11, passed = 11, failed = 0, inProgress = 0, status = CiStatus.PASSED),
            summary
        )
    }

    @Test
    fun getCheckRunsForRef_marksFailuresFromCommitStatuses() = runBlocking {
        val api = createApi(
            checkRunsBody = checkRunsResponse(listOf(CheckRunPayload(status = "completed", conclusion = "success"))),
            statusBody = statusResponse(listOf("failure")),
        )

        val summary = api.getCheckRunsForRef(owner = "test-org", repo = "test-repo", ref = "abc123")

        assertEquals(
            CheckRunSummary(total = 2, passed = 1, failed = 1, inProgress = 0, status = CiStatus.FAILED),
            summary
        )
    }

    @Test
    fun getCheckRunsForRef_marksPendingCommitStatusesAsRunning() = runBlocking {
        val api = createApi(
            checkRunsBody = checkRunsResponse(listOf(CheckRunPayload(status = "completed", conclusion = "success"))),
            statusBody = statusResponse(listOf("pending")),
        )

        val summary = api.getCheckRunsForRef(owner = "test-org", repo = "test-repo", ref = "abc123")

        assertEquals(
            CheckRunSummary(total = 2, passed = 1, failed = 0, inProgress = 1, status = CiStatus.RUNNING),
            summary
        )
    }
}

private fun createApi(checkRunsBody: String, statusBody: String): GitHubRestApi {
    val client =
        HttpClient(
            MockEngine { request ->
                val body =
                    when (request.url.encodedPath) {
                        "/repos/test-org/test-repo/commits/abc123/check-runs" -> checkRunsBody
                        "/repos/test-org/test-repo/commits/abc123/status" -> statusBody
                        else -> error("Unexpected request path: ${request.url.encodedPath}")
                    }

                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    return GitHubRestApi(GitHubApiRestConfig(token = "test-token"), client)
}

private data class CheckRunPayload(
    val status: String,
    val conclusion: String?,
)

private fun checkRunsResponse(checkRuns: List<CheckRunPayload>): String {
    val serializedRuns =
        checkRuns.joinToString(",") { checkRun ->
            val conclusionField =
                checkRun.conclusion?.let { ",\"conclusion\":\"$it\"" } ?: ",\"conclusion\":null"
            """{"status":"${checkRun.status}"$conclusionField}"""
        }

    return """{"check_runs":[$serializedRuns]}"""
}

private fun statusResponse(states: List<String>): String {
    val serializedStatuses =
        states.joinToString(",") { state ->
            """{"state":"$state","context":"$state-context"}"""
        }

    return """{"statuses":[$serializedStatuses]}"""
}
