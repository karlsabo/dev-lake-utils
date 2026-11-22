package com.github.karlsabo.gitlab

import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabMergeRequestRestApiTest {
    private val instanceUrl = "https://gitlab.internal.example"
    private val token = "glpat-synthetic-token"

    @Test
    fun listsAuthoredOpenMergeRequestsWithTheTokenOnlyInItsHeader() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                assertEquals(token, request.headers[GITLAB_TOKEN_HEADER])
                assertEquals("/api/v4/merge_requests", request.url.encodedPath)
                assertEquals("created_by_me", request.url.parameters["scope"])
                assertEquals("opened", request.url.parameters["state"])
                respondJson("[${mergeRequestJson()}]")
            },
        )
        val api = GitLabRestApi(GitLabApiRestConfig(instanceUrl, token), client)

        val mergeRequests = api.getAuthoredOpenMergeRequests()

        api.close()
        assertEquals(1, mergeRequests.size)
        val mergeRequest = mergeRequests.single()
        assertEquals(42, mergeRequest.iid)
        assertEquals("games/atlas!42", mergeRequest.references?.full)
        assertEquals("https://gitlab.internal.example/games/atlas/-/merge_requests/42", mergeRequest.webUrl)
        assertEquals("games/atlas", mergeRequest.repositoryPath())
        assertFalse(mergeRequest.isDraft)
        assertFalse(token in requestedUrls.single())
    }

    @Test
    fun followsTheNextPageHeaderUntilTheInstanceStopsReportingOne() = runBlocking {
        val requestedPages = mutableListOf<String?>()
        val client = HttpClient(
            MockEngine { request ->
                val page = request.url.parameters["page"]
                requestedPages += page
                if (page == "1") {
                    respondJson("[${mergeRequestJson()}]", nextPage = "2")
                } else {
                    respondJson(
                        """
                        [
                          ${mergeRequestJson(iid = 43, title = "Second page")}
                        ]
                        """.trimIndent(),
                    )
                }
            },
        )
        val api = GitLabRestApi(GitLabApiRestConfig(instanceUrl, token), client)

        val mergeRequests = api.getAuthoredOpenMergeRequests()

        api.close()
        assertEquals(listOf<String?>("1", "2"), requestedPages)
        assertEquals(listOf(42, 43), mergeRequests.map { it.iid })
    }

    @Test
    fun validatesTheAccountAgainstTheConfiguredInstance() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("/api/v4/user", request.url.encodedPath)
                assertEquals(token, request.headers[GITLAB_TOKEN_HEADER])
                respondJson("""{"id":7,"username":"atlas-user","name":"Atlas User"}""")
            },
        )
        val api = GitLabRestApi(GitLabApiRestConfig(instanceUrl, token), client)

        val user = api.getAuthenticatedUser()

        api.close()
        assertEquals("atlas-user", user.username)
    }

    @Test
    fun reportsTheStatusForARejectedToken() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"message":"401 Unauthorized"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = jsonHeaders(),
                )
            },
        )
        val api = GitLabRestApi(GitLabApiRestConfig(instanceUrl, token), client)

        val error = assertFailsWith<GitLabApiException> { api.getAuthoredOpenMergeRequests() }

        api.close()
        assertEquals(401, error.statusCode)
        assertTrue("401" in error.message.orEmpty())
    }

    private fun mergeRequestJson(
        iid: Int = 42,
        title: String = "Add atlas feature",
    ): String = """
        {
          "iid": $iid,
          "title": "$title",
          "web_url": "$instanceUrl/games/atlas/-/merge_requests/$iid",
          "state": "opened",
          "draft": false,
          "work_in_progress": false,
          "source_branch": "feature/atlas",
          "target_branch": "main",
          "references": {"short": "!$iid", "relative": "!$iid", "full": "games/atlas!$iid"}
        }
    """.trimIndent()

    private fun MockRequestHandleScope.respondJson(
        body: String,
        nextPage: String? = null,
    ) = respond(
        content = body,
        headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            *listOfNotNull(nextPage?.let { page -> GITLAB_NEXT_PAGE_HEADER to listOf(page) }).toTypedArray(),
        ),
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType to listOf("application/json"))
}
