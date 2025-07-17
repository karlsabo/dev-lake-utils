package com.github.karlsabo.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    // karlfixme remove these raw params
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val email: String? = null,
    val name: String,
    val slackId: String? = null,
    val gitHubId: String? = null,
    val jiraId: String? = null,
)
