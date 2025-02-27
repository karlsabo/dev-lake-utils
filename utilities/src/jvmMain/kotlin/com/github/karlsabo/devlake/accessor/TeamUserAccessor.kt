package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


interface UserTeamAccessor {
    fun insertTeamUser(teamUser: TeamUser)
    fun deleteTeamUser(teamId: String, userId: String)
    fun getTeamUsersByTeamId(teamId: String): List<TeamUser>
    fun getTeamUsersByUserId(userId: String): List<TeamUser>
}

@Serializable
data class TeamUser(
    val teamId: Long,
    val userId: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null
)
