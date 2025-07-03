package com.github.karlsabo.github

import kotlinx.serialization.Serializable

@Serializable
data class Label(
    val id: Long,
    val name: String,
    val color: String,
    val description: String? = null,
)
