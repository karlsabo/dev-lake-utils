package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssueComponent(
    val id: String? = null,
    val name: String? = null,
)
