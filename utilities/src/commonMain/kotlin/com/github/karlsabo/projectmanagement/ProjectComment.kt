package com.github.karlsabo.projectmanagement

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A unified representation of a comment across different project management systems (Jira, Linear, etc.).
 */
@Serializable
data class ProjectComment(
    val id: String,
    val body: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
