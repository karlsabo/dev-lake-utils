package com.github.karlsabo.linear

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class IssueProject(
    val id: String,
    val name: String? = null,
    val completedAt: Instant? = null,
    val canceledAt: Instant? = null,
)
