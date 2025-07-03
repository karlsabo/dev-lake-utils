package com.github.karlsabo.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val login: String,
    val id: Long,
    @SerialName("avatar_url")
    val avatarUrl: String?,
    val url: String?,
    @SerialName("html_url")
    val htmlUrl: String?,
)
