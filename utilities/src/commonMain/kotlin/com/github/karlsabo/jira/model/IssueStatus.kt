package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssueStatus(
    val id: String? = null,
    val name: String? = null,
    val statusCategory: StatusCategory? = null,
)
