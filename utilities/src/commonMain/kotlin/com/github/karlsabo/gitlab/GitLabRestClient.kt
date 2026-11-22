package com.github.karlsabo.gitlab

import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

internal class GitLabRestClient(
    private val config: GitLabApiRestConfig,
    private val clientOverride: HttpClient? = null,
) {
    private val ownedClient = lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJson)
            }
            installHttpRetry()
            expectSuccess = false
        }
    }
    val client: HttpClient by lazy { clientOverride ?: ownedClient.value }

    fun close() {
        if (ownedClient.isInitialized()) ownedClient.value.close()
    }

    suspend fun getResponse(
        url: String,
        operation: String,
    ): HttpResponse {
        val response = client.get(url) {
            // Ktor's redirect handling strips Authorization on cross-authority hops, so the token cannot follow a
            // redirect to a different host. A custom header would be forwarded.
            header(HttpHeaders.Authorization, "Bearer ${config.token}")
            header(HttpHeaders.Accept, "application/json")
        }
        if (response.status.value !in gitLabSuccessStatusCodes) {
            val responseText = response.bodyAsText()
            throwGitLabApiException(operation, response.status.value, responseText)
        }
        return response
    }
}
