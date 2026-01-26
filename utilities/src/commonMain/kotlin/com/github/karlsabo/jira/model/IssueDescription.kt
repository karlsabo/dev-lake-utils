package com.github.karlsabo.jira.model

import com.github.karlsabo.common.adf.ContentNode
import kotlinx.serialization.Serializable

@Serializable
data class IssueDescription(
    val type: String? = null,
    val version: Int? = null,
    val content: List<ContentNode>? = null,
)
