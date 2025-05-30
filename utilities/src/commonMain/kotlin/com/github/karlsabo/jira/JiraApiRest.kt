package com.github.karlsabo.jira

import com.github.karlsabo.Credentials
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

data class JiraApiRestConfig(
    val credentials: Credentials,
    val domain: String,
)

class JiraApiRest(private val config: JiraApiRestConfig) : JiraApi {
    private val client: HttpClient = HttpClient(CIO) {
        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(
                        username = config.credentials.username,
                        password = config.credentials.password,
                    )
                }
                sendWithoutRequest { true }
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment> {
        val commentList = mutableListOf<Comment>()
        val url = "https://${config.domain}/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=$maxResults"
        val response = client.get(url)
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val comments = root["comments"]?.jsonArray ?: return commentList
        commentList += comments.map { it.jsonObject.toComment() }

        return commentList
    }

    override suspend fun runJql(jql: String): List<Issue> {
        val issueList = mutableListOf<Issue>()
        var startAt = 0
        val maxResults = 100

        while (true) {
            val encodedJql = jql.encodeURLParameter()
            val url =
                "https://${config.domain}/rest/api/3/search?jql=$encodedJql&startAt=$startAt&maxResults=$maxResults"

            val response = client.get(url)
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            val issues = root["issues"]?.jsonArray ?: break
            issueList += issues.map { it.jsonObject.toIssue() }

            val total = root["total"]?.jsonPrimitive?.int ?: break
            startAt += maxResults

            if (startAt >= total) break
        }

        return issueList
    }
}