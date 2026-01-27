package com.github.karlsabo.http

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installHttpRetry() {
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnException(maxRetries, true)
        retryIf { _, response ->
            response.status == HttpStatusCode.TooManyRequests ||
                    response.status == HttpStatusCode.Forbidden ||
                    response.status.value.let { it >= 500 && it <= 599 }
        }
        modifyRequest { request ->
            request.headers.append("X-Ktor-Retry-Count", retryCount.toString())
            logger.info { "Retrying request, attempt: ${retryCount + 1}" }
        }
        exponentialDelay(2.0, 10_000, 5.minutes.inWholeMilliseconds, 5_000, true)
    }
}
