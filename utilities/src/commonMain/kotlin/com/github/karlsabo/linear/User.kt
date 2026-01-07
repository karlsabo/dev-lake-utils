package com.github.karlsabo.linear

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val name: String? = null,
    val displayName: String? = null,
    val email: String? = null,
)
