package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface UserAccountAccessor {
    fun getUserAccountByUserId(userId: String): List<UserAccount>
    fun getUserAccountByAccountId(accountId: String): UserAccount?
}

@Serializable
data class UserAccount(
    val userId: String? = null,
    val accountId: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null
)
