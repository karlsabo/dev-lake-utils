package com.github.karlsabo.jira

import com.github.karlsabo.Credentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JiraRestApiPaginationTest {
    @Test
    fun runJql_paginatesWithNextPageToken() = runBlocking {
        val firstPage =
            """
            {
              "isLast": false,
              "nextPageToken": "token-1",
              "issues": [
                { "id": "1", "key": "TEST-1", "self": "https://example.local/rest/api/3/issue/1", "fields": {} },
                { "id": "2", "key": "TEST-2", "self": "https://example.local/rest/api/3/issue/2", "fields": {} }
              ]
            }
            """.trimIndent()

        val secondPage =
            """
            {
              "isLast": true,
              "issues": [
                { "id": "3", "key": "TEST-3", "self": "https://example.local/rest/api/3/issue/3", "fields": {} }
              ]
            }
            """.trimIndent()

        val requestedTokens = mutableListOf<String?>()
        val client =
            HttpClient(
                MockEngine { request ->
                    val token = request.url.parameters["nextPageToken"]
                    requestedTokens += token
                    val body = if (token == null) firstPage else secondPage
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )

        val api =
            JiraRestApi(
                JiraApiRestConfig(
                    credentials = Credentials("user", "pass"),
                    domain = "example.local",
                ),
                client,
            )

        val issues = api.runJql("assignee = currentUser() ORDER BY updated DESC")
        assertEquals(listOf("TEST-1", "TEST-2", "TEST-3"), issues.map { it.key })
        assertEquals(listOf(null, "token-1"), requestedTokens)
    }
}

