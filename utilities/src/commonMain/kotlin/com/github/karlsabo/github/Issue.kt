package com.github.karlsabo.github

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Issue(
    val url: String?,
    @SerialName("repository_url")
    val repositoryUrl: String?,
    val id: Long,
    val number: Int,
    val state: String,
    val title: String,
    val user: User,
    val body: String?,
    @SerialName("html_url")
    val htmlUrl: String,
    val labels: List<Label> = emptyList(),
    val draft: Boolean,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("updated_at")
    val updatedAt: Instant?,
    @SerialName("closed_at")
    val closedAt: Instant?,
    @SerialName("pull_request")
    val pullRequest: PullRequest? = null,
    val comments: Int?,
)
