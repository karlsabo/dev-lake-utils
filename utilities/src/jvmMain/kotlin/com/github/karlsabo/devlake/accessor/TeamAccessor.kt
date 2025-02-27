package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface TeamAccessor {
    fun getTeamById(id: String): Team?
    fun getTeamByName(name: String): Team?
    fun getTeamByAlias(alias: String): Team?
}

@Serializable
data class Team(
    val id: Long,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val name: String? = null,
    val alias: String? = null,
    val parentId: String? = null,
    val sortingIndex: Long? = null
)
