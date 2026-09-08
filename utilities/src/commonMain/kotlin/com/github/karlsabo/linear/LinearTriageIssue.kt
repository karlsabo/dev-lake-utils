package com.github.karlsabo.linear

import kotlin.time.Instant

/** Linear-owned issue data needed to build a triage inventory. */
data class LinearTriageIssue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String?,
    val url: String?,
    val stateName: String?,
    val stateType: String? = null,
    val projectName: String?,
    val labelNames: Set<String>,
    val updatedAt: Instant?,
    val completedAt: Instant? = null,
    val canceledAt: Instant? = null,
    val archivedAt: Instant? = null,
)
