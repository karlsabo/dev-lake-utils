package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class UserAccountAccessorDb(val datasource: DataSource) : UserAccountAccessor {
    override fun getUserAccountByUserId(userId: String): List<UserAccount> {
        val userAccounts = mutableListOf<UserAccount>()
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    user_accounts
                WHERE
                    user_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        userAccounts.add(resultSetToUserAccount(resultSet))
                    }
                }
            }
        }
        return userAccounts
    }

    override fun getUserAccountByAccountId(accountId: String): UserAccount? {
        datasource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    *
                FROM
                    user_accounts
                WHERE
                    account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, accountId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSetToUserAccount(resultSet)
                    }
                }
            }
        }
        return null
    }

    private fun resultSetToUserAccount(resultSet: ResultSet): UserAccount = UserAccount(
        resultSet.getString("user_id"),
        resultSet.getString("account_id"),
        resultSet.getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        resultSet.getString("_raw_data_params"),
        resultSet.getString("_raw_data_table"),
        resultSet.getLong("_raw_data_id"),
        resultSet.getString("_raw_data_remark")
    )
}
