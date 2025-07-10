package com.github.karlsabo.http

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installHttpRetry() {
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnException(maxRetries, true)
        retryIf { _, response ->
            response.status == HttpStatusCode.TooManyRequests ||
                    response.status.value.let { it >= 500 && it <= 599 }
        }
        modifyRequest { request ->
            request.headers.append("X-Ktor-Retry-Count", retryCount.toString())
            println("Retrying request, attempt: ${retryCount + 1}")
        }
        exponentialDelay(2.0, 1_000, 60_000, 5_000, true)
    }
}
