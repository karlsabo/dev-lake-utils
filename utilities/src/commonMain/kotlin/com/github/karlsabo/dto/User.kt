package com.github.karlsabo.dto

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class User(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val email: String? = null,
    val name: String,
    val slackId: String? = null,
    val gitHubId: String? = null,
    val jiraId: String? = null,
    val linearId: String? = null,
)
