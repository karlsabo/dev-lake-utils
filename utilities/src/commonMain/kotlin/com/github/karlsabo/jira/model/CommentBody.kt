package com.github.karlsabo.jira.model

import com.github.karlsabo.common.adf.ContentNode
import kotlinx.serialization.Serializable

@Serializable
data class CommentBody(
    val type: String,
    val version: Int,
    val content: List<ContentNode>,
)
