package com.github.karlsabo.linear

import kotlinx.serialization.Serializable

@Serializable
data class IssueParent(
    val id: String,
    val identifier: String? = null,
    val title: String? = null,
)
