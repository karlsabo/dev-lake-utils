package com.github.karlsabo.pagerduty

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PagerDutyIncident(
    val id: String,
    val incidentNumber: Int,
    val title: String,
    val description: String?,
    val status: String,
    val urgency: String,
    val serviceId: String,
    val serviceName: String,
    val createdAt: Instant,
    val lastStatusChangeAt: Instant?,
    val resolvedAt: Instant?,
    val htmlUrl: String,
)
