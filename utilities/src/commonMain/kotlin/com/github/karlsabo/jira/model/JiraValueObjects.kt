package com.github.karlsabo.jira.model

import com.github.karlsabo.common.adf.ContentNode
import kotlinx.serialization.SerialName
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

@Serializable
data class JiraAvatarUrls(
    @SerialName("48x48") val size48x48: String,
    @SerialName("24x24") val size24x24: String,
    @SerialName("16x16") val size16x16: String,
    @SerialName("32x32") val size32x32: String,
)

@Serializable
data class CustomFieldValue(
    val id: String? = null,
    val value: String? = null,
)

@Serializable
data class EpicLink(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)

@Serializable
data class IssueParent(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)

@Serializable
data class IssueComponent(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class IssueProject(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)

@Serializable
data class IssueDescription(
    val type: String? = null,
    val version: Int? = null,
    val content: List<ContentNode>? = null,
)
