package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val rawDataParams: String?,
    val rawDataTable: String?,
    val rawDataId: Long?,
    val rawDataRemark: String?,
    val email: String?,
    val fullName: String?,
    val userName: String?,
    val avatarUrl: String?,
    val organization: String?,
    val createdDate: Instant?,
    val status: Long?
)

interface AccountAccessor {
    fun getAccountById(id: String): Account?
    fun getAccountByEmail(email: String): Account?
    fun getAccountByFullName(fullName: String): Account?
    fun getAccountByUserName(userName: String): Account?
}
