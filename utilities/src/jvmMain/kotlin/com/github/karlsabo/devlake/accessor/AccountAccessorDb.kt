package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class AccountAccessorDb(private val dataSource: DataSource) : AccountAccessor {
    private fun ResultSet.toAccount(): Account = Account(
        id = getString("id"),
        createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        rawDataParams = getString("_raw_data_params"),
        rawDataTable = getString("_raw_data_table"),
        rawDataId = getLong("_raw_data_id"),
        rawDataRemark = getString("_raw_data_remark"),
        email = getString("email"),
        fullName = getString("full_name"),
        userName = getString("user_name"),
        avatarUrl = getString("avatar_url"),
        organization = getString("organization"),
        createdDate = getTimestamp("created_date")?.toInstant()?.toKotlinInstant(),
        status = getLong("status")
    )

    override fun getAccountById(id: String): Account? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }

    override fun getAccountByEmail(email: String): Account? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE email = ?").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }

    override fun getAccountByFullName(fullName: String): Account? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE full_name = ?").use { stmt ->
                stmt.setString(1, fullName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }

    override fun getAccountByUserName(userName: String): Account? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE user_name = ?").use { stmt ->
                stmt.setString(1, userName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }
}
