package com.github.karlsabo.linear

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val MISSING_DATA_MESSAGE = "Linear GraphQL response missing data: "

@Serializable
private data class GraphQlRequest(
    val query: String,
    val variables: JsonObject? = null,
)

internal class LinearGraphQlClient(
    private val config: LinearApiRestConfig,
    private val clientOverride: HttpClient?,
) {
    private val client: HttpClient by lazy {
        clientOverride ?: buildDefaultClient()
    }

    suspend fun execute(query: String, variables: JsonObject? = null): JsonObject {
        val response = client.post(config.endpoint) {
            header(HttpHeaders.Accept, ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(GraphQlRequest(query, variables))
        }

        val responseText = response.bodyAsText()
        ensureGraphQlHttpSuccess(response.status, responseText)

        val root = lenientJson.parseToJsonElement(responseText).jsonObject
        ensureGraphQlResponseHasNoErrors(root, query)

        return root["data"]?.jsonObject ?: missingGraphQlData(responseText)
    }

    private fun buildDefaultClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(lenientJson)
        }
        defaultRequest {
            header(HttpHeaders.Authorization, authorizationHeaderValue())
            header(HttpHeaders.Accept, ContentType.Application.Json)
        }
        installHttpRetry()
        install(HttpCache)
        expectSuccess = false
    }

    private fun authorizationHeaderValue(): String {
        if (config.token.startsWith("Bearer ")) return config.token
        return if (config.useBearerAuth) {
            "Bearer ${config.token}"
        } else {
            config.token
        }
    }
}

private fun ensureGraphQlHttpSuccess(status: HttpStatusCode, responseText: String) {
    if (status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
        logger.debug { "Linear GraphQL error response: ```$responseText```" }
        throw LinearApiException("Failed Linear GraphQL request: ${status.value}")
    }
}

private fun ensureGraphQlResponseHasNoErrors(root: JsonObject, query: String) {
    val errors = root["errors"]?.jsonArray
        ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.content }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    if (errors.isNotEmpty()) {
        val errorDetails = root["errors"]?.jsonArray
            ?.map { it.jsonObject.toString() }
            ?.joinToString("\n") ?: ""
        logger.error { "Linear GraphQL errors for query:\n$query\nErrors:\n$errorDetails" }
        throw LinearApiException("Linear GraphQL errors: ${errors.joinToString("; ")}")
    }
}

private fun missingGraphQlData(text: String): Nothing = throw LinearApiException(MISSING_DATA_MESSAGE + text)
