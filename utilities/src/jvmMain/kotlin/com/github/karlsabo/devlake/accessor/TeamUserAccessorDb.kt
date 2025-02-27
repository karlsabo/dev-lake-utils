package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

class TeamUserAccessorDb(private val dataSource: DataSource) : UserTeamAccessor {
    override fun insertTeamUser(teamUser: TeamUser) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO team_users (team_id, user_id, created_at, updated_at, _raw_data_params, _raw_data_table, _raw_data_id, _raw_data_remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    created_at = VALUES(created_at),
                    updated_at = VALUES(updated_at),
                    _raw_data_params = VALUES(_raw_data_params),
                    _raw_data_table = VALUES(_raw_data_table),
                    _raw_data_id = VALUES(_raw_data_id),
                    _raw_data_remark = VALUES(_raw_data_remark)
                """.trimIndent()
            ).use { ps ->
                ps.populateFromTeamUser(0, teamUser)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteTeamUser(teamId: String, userId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM
                    team_users
                WHERE
                    team_id = ? AND user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, teamId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    override fun getTeamUsersByTeamId(teamId: String): List<TeamUser> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    team_id, user_id, created_at, updated_at, _raw_data_params, _raw_data_table, _raw_data_id, _raw_data_remark
                FROM
                    team_users
                WHERE
                    team_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, teamId)
                ps.executeQuery().use { rs ->
                    val teamUsers = mutableListOf<TeamUser>()
                    while (rs.next()) {
                        teamUsers.add(rs.toTeamUser())
                    }
                    return teamUsers
                }
            }
        }
    }

    override fun getTeamUsersByUserId(userId: String): List<TeamUser> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT team_id, user_id, created_at, updated_at, _raw_data_params, _raw_data_table, _raw_data_id, _raw_data_remark
                FROM team_users
                WHERE user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val teamUsers = mutableListOf<TeamUser>()
                    while (rs.next()) {
                        teamUsers.add(rs.toTeamUser())
                    }
                    return teamUsers
                }
            }
        }
    }
}

private fun ResultSet.toTeamUser(): TeamUser {
    return TeamUser(
        teamId = getString("team_id").toLong(),
        userId = getString("user_id"),
        createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        rawDataParams = getString("_raw_data_params"),
        rawDataTable = getString("_raw_data_table"),
        rawDataId = getLong("_raw_data_id"),
        rawDataRemark = getString("_raw_data_remark")
    )
}

private fun PreparedStatement.populateFromTeamUser(offset: Int, teamUser: TeamUser) {
    setString(offset + 1, teamUser.teamId.toString())
    setString(offset + 2, teamUser.userId)
    setTimestamp(offset + 3, teamUser.createdAt?.let { Timestamp.from(it.toJavaInstant()) })
    setTimestamp(offset + 4, teamUser.updatedAt?.let { Timestamp.from(it.toJavaInstant()) })
    setString(offset + 5, teamUser.rawDataParams)
    setString(offset + 6, teamUser.rawDataTable)
    setLong(offset + 7, teamUser.rawDataId ?: 0)
    setString(offset + 8, teamUser.rawDataRemark)
}
