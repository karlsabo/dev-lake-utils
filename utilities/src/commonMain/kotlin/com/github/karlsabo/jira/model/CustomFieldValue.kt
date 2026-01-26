package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomFieldValue(
    val id: String? = null,
    val value: String? = null,
)
