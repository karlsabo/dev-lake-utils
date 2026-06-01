package com.github.karlsabo.http

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.minutes

private const val MAX_RETRIES = 3
private const val RETRY_BASE_DELAY_MS = 10_000L
private const val RETRY_RANDOMIZATION_MS = 5_000L
private const val NEXT_RETRY_ATTEMPT_OFFSET = 1
private const val RETRY_EXPONENT = 2.0
private const val SERVER_ERROR_STATUS_MIN = 500
private const val SERVER_ERROR_STATUS_MAX = 599

private val logger = KotlinLogging.logger {}
private val serverErrorStatusRange = SERVER_ERROR_STATUS_MIN..SERVER_ERROR_STATUS_MAX

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installHttpRetry() {
    install(HttpRequestRetry) {
        maxRetries = MAX_RETRIES
        retryOnException(maxRetries, true)
        retryIf { _, response ->
            response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.Forbidden ||
                response.status.value in serverErrorStatusRange
        }
        modifyRequest { request ->
            request.headers.append("X-Ktor-Retry-Count", retryCount.toString())
            logger.info { "Retrying request, attempt: ${retryCount + NEXT_RETRY_ATTEMPT_OFFSET}" }
        }
        exponentialDelay(
            base = RETRY_EXPONENT,
            baseDelayMs = RETRY_BASE_DELAY_MS,
            maxDelayMs = 5.minutes.inWholeMilliseconds,
            randomizationMs = RETRY_RANDOMIZATION_MS,
            respectRetryAfterHeader = true,
        )
    }
}
