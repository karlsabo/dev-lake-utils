package com.github.karlsabo.pagerduty

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagerDutyIncident(
    val id: String,
    @SerialName("incident_number")
    val incidentNumber: Int,
    @SerialName("incident_key")
    val incidentKey: String? = null,
    val title: String,
    val description: String?,
    val status: String,
    val urgency: String,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant,
    @SerialName("last_status_change_at")
    val lastStatusChangeAt: Instant?,
    @SerialName("resolved_at")
    val resolvedAt: Instant?,
    @SerialName("html_url")
    val htmlUrl: String,
    val service: Service? = null,
    val summary: String? = null,
)

@Serializable
data class Service(
    val id: String,
    val type: String,
    val summary: String,
    @SerialName("self")
    val selfUrl: String,
    @SerialName("html_url")
    val htmlUrl: String,
)
