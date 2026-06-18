package com.github.karlsabo.linear

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Comment(
    val id: String,
    val body: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val url: String? = null,
    val user: User? = null,
    val archivedAt: Instant? = null,
)
