package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssueType(
    val id: String? = null,
    val name: String? = null,
    val iconUrl: String? = null,
    val subtask: Boolean? = null,
)
