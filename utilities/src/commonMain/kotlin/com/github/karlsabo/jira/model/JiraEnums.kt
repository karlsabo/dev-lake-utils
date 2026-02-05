package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssueType(
    val id: String? = null,
    val name: String? = null,
    val iconUrl: String? = null,
    val subtask: Boolean? = null,
)

@Serializable
data class IssueStatus(
    val id: String? = null,
    val name: String? = null,
    val statusCategory: StatusCategory? = null,
)

@Serializable
data class StatusCategory(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)

@Serializable
data class IssuePriority(
    val id: String? = null,
    val name: String? = null,
)
