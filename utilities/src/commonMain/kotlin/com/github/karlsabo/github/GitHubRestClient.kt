package com.github.karlsabo.github

import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

private val logger = KotlinLogging.logger {}

internal class GitHubRestClient(
    private val config: GitHubApiRestConfig,
    private val clientOverride: HttpClient? = null,
) {
    val client: HttpClient by lazy {
        clientOverride ?: HttpClient(CIO) {
            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(config.token, "")
                    }
                    sendWithoutRequest { true }
                }
            }
            install(ContentNegotiation) {
                json(lenientJson)
            }
            defaultRequest {
                header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            installHttpRetry()
            install(HttpCache)
            expectSuccess = false
        }
    }

    suspend fun getSuccessfulResponseText(
        url: String,
        operation: String,
        context: String,
    ): String {
        val response = client.get(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error {
                "Failed to $operation $context response.status=${response.status} responseText=```$responseText```"
            }
            throwGitHubApiException(operation, response.status.value, context, responseText)
        }
        return responseText
    }
}
