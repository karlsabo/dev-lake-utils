package com.github.karlsabo.devlake.metrics.service

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

private const val HTTP_SUCCESS_STATUS_MIN = 200
private const val HTTP_SUCCESS_STATUS_MAX = 299

private val logger = KotlinLogging.logger {}

@Serializable
data class SlackMessage(
    val userEmail: String,
    val message: String,
)

class ZapierMetricSendException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object ZapierMetricService {
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJson)
            }
            installHttpRetry()
            install(HttpCache)
            expectSuccess = false
        }
    }

    suspend fun sendMessage(message: SlackMessage, zapierUrl: String): Boolean {
        logger.info { "Sending to ${message.userEmail}" }
        return runCatching {
            postMessage(message, zapierUrl)
        }.getOrElse { error ->
            val sendError = error.toZapierMetricSendException()
            logger.error(sendError) { "Failed to send message to Zapier" }
            false
        }
    }

    private suspend fun postMessage(message: SlackMessage, zapierUrl: String): Boolean {
        val response = client.post(zapierUrl) {
            header(HttpHeaders.Referrer, "https://hooks.zapier.com")
            contentType(ContentType.Application.Json)
            setBody(lenientJson.encodeToString(SlackMessage.serializer(), message))
        }

        val responseBody = response.bodyAsText()
        logger.debug { "Zapier response status=${response.status}, body=$responseBody" }

        if (response.status.value !in HTTP_SUCCESS_STATUS_MIN..HTTP_SUCCESS_STATUS_MAX) {
            throw ZapierMetricSendException("Zapier request failed: HTTP ${response.status.value}")
        }

        return true
    }
}

private fun Throwable.toZapierMetricSendException(): ZapierMetricSendException {
    val sendException = this as? ZapierMetricSendException
    return sendException ?: ZapierMetricSendException("Zapier request failed", this)
}
