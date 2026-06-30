package com.github.karlsabo.linear

import kotlinx.serialization.Serializable

@Serializable
data class IssueProject(
    val id: String,
    val name: String? = null,
)
