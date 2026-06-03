package com.github.karlsabo.devlake.tools.service

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

private const val HTTP_SUCCESS_STATUS_MIN = 200
private const val HTTP_SUCCESS_STATUS_MAX = 299

private val logger = KotlinLogging.logger {}

@Serializable
data class ZapierProjectSummary(
    val message: String,
    val projectMessages: List<String>,
)

class ZapierSummarySendException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object ZapierService {
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

    suspend fun sendSummary(summary: ZapierProjectSummary, zapierUrl: String): Boolean = try {
        postSummary(summary, zapierUrl)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ZapierSummarySendException) {
        logSendFailure(error)
    } catch (error: ResponseException) {
        logSendFailure(error)
    } catch (error: URLParserException) {
        logSendFailure(error)
    } catch (error: IOException) {
        logSendFailure(error)
    } catch (error: SerializationException) {
        logSendFailure(error)
    }

    private suspend fun postSummary(summary: ZapierProjectSummary, zapierUrl: String): Boolean {
        val response = client.post(zapierUrl) {
            header(HttpHeaders.Referrer, "https://hooks.zapier.com")
            contentType(ContentType.Application.Json)
            setBody(lenientJson.encodeToString(ZapierProjectSummary.serializer(), summary))
        }

        val responseBody = response.bodyAsText()
        logger.debug { "Zapier response status=${response.status}, body=$responseBody" }

        if (response.status.value !in HTTP_SUCCESS_STATUS_MIN..HTTP_SUCCESS_STATUS_MAX) {
            logger.error { "Zapier request failed: HTTP ${response.status.value}" }
            return false
        }

        return true
    }
}

private fun logSendFailure(error: Throwable): Boolean {
    val sendError = error.toZapierSummarySendException()
    logger.error(sendError) { "Failed to send summary to Zapier" }
    return false
}

private fun Throwable.toZapierSummarySendException(): ZapierSummarySendException {
    val sendException = this as? ZapierSummarySendException
    return sendException ?: ZapierSummarySendException("Zapier request failed", this)
}
