package com.github.karlsabo.common.adf

import kotlinx.serialization.Serializable

/**
 * Represents a node in the Atlassian Document Format (ADF).
 * ADF is used by Jira for rich text content like descriptions and comments.
 */
@Serializable
data class ContentNode(
    val type: String,
    val content: List<ContentNode>? = null,
    val attrs: ContentAttrs? = null,
    val text: String? = null,
)
