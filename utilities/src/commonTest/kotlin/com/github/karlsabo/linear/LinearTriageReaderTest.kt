package com.github.karlsabo.linear

import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LinearTriageReaderTest {
    @Test
    fun `requests archived issues and returns their lifecycle metadata`() = runTest {
        val responses = ArrayDeque(listOf(projectResponse, labelResponse))
        val requestBodies = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("https://linear.test/graphql", request.url.toString())
                requestBodies += request.body.toByteArray().decodeToString()
                respond(
                    content = responses.removeFirst(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(lenientJson) }
        }
        val reader = LinearTriageReader(
            config = LinearApiRestConfig(token = "test", endpoint = "https://linear.test/graphql"),
            clientOverride = client,
        )

        val issues = reader.getIssues("TST", "Test Project", "test-label")

        assertEquals(listOf("TST-1", "TST-2", "TST-3"), issues.map(LinearTriageIssue::identifier))
        assertEquals(setOf("test-label"), issues.first().labelNames)
        assertEquals(Instant.parse("2026-04-03T11:00:00Z"), issues.last().archivedAt)
        assertTrue(requestBodies.all { "includeArchived: true" in it })
        assertEquals(0, responses.size)
    }

    private companion object {
        val projectResponse = response(
            issue("id-1", "TST-1", "Test Project", listOf("test-label")),
            issue("id-2", "TST-2", "Test Project", emptyList()),
        )
        val labelResponse = response(
            issue("id-1", "TST-1", "Test Project", listOf("test-label")),
            issue(
                id = "id-3",
                identifier = "TST-3",
                projectName = null,
                labels = listOf("test-label"),
                archivedAt = "2026-04-03T11:00:00Z",
            ),
        )

        fun response(vararg issues: String): String {
            val nodes = issues.joinToString()
            return """{"data":{"issues":{"nodes":[$nodes],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""
        }

        fun issue(
            id: String,
            identifier: String,
            projectName: String?,
            labels: List<String>,
            archivedAt: String? = null,
        ): String {
            val project = projectName?.let { "{\"id\":\"project\",\"name\":\"$it\"}" } ?: "null"
            val labelNodes = labels.joinToString { "{\"id\":\"$it\",\"name\":\"$it\"}" }
            val archivedAtValue = archivedAt?.let { "\"$it\"" } ?: "null"
            return """
                {
                  "id":"$id",
                  "identifier":"$identifier",
                  "title":"Title $identifier",
                  "url":"https://linear/$identifier",
                  "updatedAt":"2026-04-02T00:00:00Z",
                  "archivedAt":$archivedAtValue,
                  "state":{"id":"state","name":"started","type":"started"},
                  "project":$project,
                  "labels":{"nodes":[$labelNodes]}
                }
            """.trimIndent()
        }
    }
}
