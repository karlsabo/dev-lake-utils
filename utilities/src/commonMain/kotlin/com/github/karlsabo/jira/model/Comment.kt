package com.github.karlsabo.jira.model

import com.github.karlsabo.jira.serialization.CustomInstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val self: String,
    val id: String,
    val author: JiraUser,
    val body: CommentBody,
    val updateAuthor: JiraUser,
    @Serializable(with = CustomInstantSerializer::class)
    val created: Instant,
    @Serializable(with = CustomInstantSerializer::class)
    val updated: Instant,
    val parentId: Long? = null,
    val jsdPublic: Boolean? = null,
)
