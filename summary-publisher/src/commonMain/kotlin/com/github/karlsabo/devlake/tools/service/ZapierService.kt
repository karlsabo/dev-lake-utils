package com.github.karlsabo.devlake.tools.service

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

private val logger = KotlinLogging.logger {}

@Serializable
data class ZapierProjectSummary(val message: String, val projectMessages: List<String>)

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

    suspend fun sendSummary(summary: ZapierProjectSummary, zapierUrl: String): Boolean {
        return try {
            val response = client.post(zapierUrl) {
                header(HttpHeaders.Referrer, "https://hooks.zapier.com")
                contentType(ContentType.Application.Json)
                setBody(lenientJson.encodeToString(ZapierProjectSummary.serializer(), summary))
            }

            val responseBody = response.bodyAsText()
            logger.debug { "Zapier response status=${response.status}, body=$responseBody" }

            if (response.status.value !in 200..299) {
                logger.error { "Zapier request failed: HTTP ${response.status.value}" }
                return false
            }

            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send summary to Zapier" }
            false
        }
    }
}
