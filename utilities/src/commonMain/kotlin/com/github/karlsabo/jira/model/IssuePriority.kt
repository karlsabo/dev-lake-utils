package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssuePriority(
    val id: String? = null,
    val name: String? = null,
)
