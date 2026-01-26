package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class JiraUser(
    val self: String,
    val accountId: String,
    val emailAddress: String? = null,
    val avatarUrls: JiraAvatarUrls,
    val displayName: String,
    val active: Boolean,
    val timeZone: String,
    val accountType: String,
)
