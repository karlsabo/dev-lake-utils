package com.github.karlsabo.dto

import kotlinx.serialization.Serializable

@Serializable
data class UsersConfig(
    val users: List<User>,
)
