package com.github.karlsabo.jira

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.jira.config.JiraApiRestConfig
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val logger = KotlinLogging.logger {}

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

internal class JiraHttpApi(
    private val config: JiraApiRestConfig,
    private val clientOverride: HttpClient?,
) {
    private val client: HttpClient by lazy {
        clientOverride ?: buildDefaultClient()
    }

    suspend fun getJson(url: String, operation: String): JsonObject {
        val response = client.get(url).also { it.ensureSuccess(operation) }
        return lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun postJson(
        url: String,
        body: Map<String, String>,
        operation: String,
    ): JsonObject {
        val response = client.post(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.also { it.ensureSuccess(operation) }
        return lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun buildDefaultClient(): HttpClient = HttpClient(CIO) {
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
            json(lenientJson)
        }
        installHttpRetry()
        install(HttpCache)
        expectSuccess = false
    }
}

private suspend fun HttpResponse.ensureSuccess(operation: String) {
    if (status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
        val responseBody = bodyAsText()
        logger.debug { "Jira API error response: ```$responseBody```" }
        throw JiraApiException("Failed to $operation: HTTP ${status.value}")
    }
}
