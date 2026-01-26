package com.github.karlsabo.jira.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JiraAvatarUrls(
    @SerialName("48x48") val size48x48: String,
    @SerialName("24x24") val size24x24: String,
    @SerialName("16x16") val size16x16: String,
    @SerialName("32x32") val size32x32: String,
)
