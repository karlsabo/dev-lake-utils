package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class StatusCategory(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)
