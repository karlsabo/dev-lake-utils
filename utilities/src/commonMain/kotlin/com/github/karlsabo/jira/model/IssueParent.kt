package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class IssueParent(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)
