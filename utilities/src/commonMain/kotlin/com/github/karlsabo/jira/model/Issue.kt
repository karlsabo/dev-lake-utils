package com.github.karlsabo.jira.model

import io.ktor.http.Url
import io.ktor.http.hostWithPort
import kotlinx.serialization.Serializable

@Serializable
data class Issue(
    val id: String,
    val key: String,
    val self: String,
    val fields: JiraIssueFields,
) {
    val htmlUrl: String
        get() {
            val originalUrl = Url(this.self ?: "https://example.local")
            return "${originalUrl.protocol.name}://${originalUrl.hostWithPort}/browse/$key"
        }
}
