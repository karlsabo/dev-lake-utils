package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class UserAccessorDb(val datasource: DataSource) : UserAccessor {
    override fun getUsers(): List<User> {
        val users = mutableListOf<User>()
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 
                    *
                FROM 
                    users
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val user = resultSetToUser(resultSet)
                        users.add(user)
                    }
                }
            }
        }
        return users
    }

    override fun getUserByEmail(email: String): User? {
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    users
                WHERE
                    email = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSetToUser(resultSet)
                    }
                }
            }
        }
        return null
    }

    private fun resultSetToUser(resultSet: ResultSet): User = User(
        resultSet.getString("id"),
        resultSet.getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getString("_raw_data_params"),
        resultSet.getString("_raw_data_table"),
        resultSet.getLong("_raw_data_id"),
        resultSet.getString("_raw_data_remark"),
        resultSet.getString("email"),
        resultSet.getString("name")
    )
}
