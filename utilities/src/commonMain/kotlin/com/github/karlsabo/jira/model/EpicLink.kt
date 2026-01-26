package com.github.karlsabo.jira.model

import kotlinx.serialization.Serializable

@Serializable
data class EpicLink(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)
