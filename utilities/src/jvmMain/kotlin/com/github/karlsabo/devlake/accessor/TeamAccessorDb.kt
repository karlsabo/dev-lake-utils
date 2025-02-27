package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class TeamAccessorDb(val datasource: DataSource) : TeamAccessor {
    override fun getTeamById(id: String): Team? {
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    teams
                WHERE
                    id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSetToTeam(resultSet)
                    }
                }
            }
        }
        return null
    }

    override fun getTeamByName(name: String): Team? {
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    teams
                WHERE
                    name = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSetToTeam(resultSet)
                    }
                }
            }
        }
        return null
    }

    override fun getTeamByAlias(alias: String): Team? {
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    teams
                WHERE
                    alias = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, alias)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSetToTeam(resultSet)
                    }
                }
            }
        }
        return null
    }

    private fun resultSetToTeam(resultSet: ResultSet): Team = Team(
        resultSet.getString("id").toLong(),
        resultSet.getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getString("_raw_data_params"),
        resultSet.getString("_raw_data_table"),
        resultSet.getLong("_raw_data_id"),
        resultSet.getString("_raw_data_remark"),
        resultSet.getString("name"),
        resultSet.getString("alias"),
        resultSet.getString("parent_id"),
        resultSet.getLong("sorting_index")
    )
}
