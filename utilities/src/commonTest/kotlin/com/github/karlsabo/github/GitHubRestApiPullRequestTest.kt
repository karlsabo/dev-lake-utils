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

class GitHubRestApiPullRequestTest {
    @Test
    fun getPullRequestReadsHeadRepositoryMetadata() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("/repos/owner/dev-lake-utils/pulls/123", request.url.encodedPath)
                respond(
                    content = """
                        {
                          "number": 123,
                          "state": "closed",
                          "merged_at": "2025-01-02T03:04:05Z",
                          "head": {
                            "ref": "feature/pr-worktree",
                            "repo": { "full_name": "owner/dev-lake-utils" }
                          }
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val api = GitHubRestApi(GitHubApiRestConfig(token = "test-token"), client)

        val pullRequest = api.getPullRequest("owner", "dev-lake-utils", 123)

        assertEquals(123, pullRequest.number)
        assertEquals("feature/pr-worktree", pullRequest.head?.ref)
        assertEquals("owner/dev-lake-utils", pullRequest.head?.repo?.fullName)
        assertEquals(true, pullRequest.isMerged)
    }
}
