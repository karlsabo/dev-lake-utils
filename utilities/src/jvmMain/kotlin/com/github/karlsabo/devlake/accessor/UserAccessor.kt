package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface UserAccessor {
    fun getUsers(): List<User>
    fun getUserByEmail(email: String): User?
}

@Serializable
data class User(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val email: String? = null,
    val name: String,
    val slackId: String? = null,
)
