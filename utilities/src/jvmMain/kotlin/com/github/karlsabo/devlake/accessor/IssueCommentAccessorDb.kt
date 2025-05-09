package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class IssueCommentAccessorDb(private val dataSource: DataSource) : IssueCommentAccessor {

    override fun getPaginatedCommentsByIssueIds(issueIds: Set<String>, limit: Int, offset: Int): List<IssueComment> {
        val comments = mutableListOf<IssueComment>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT * FROM issue_comments
                WHERE issue_id IN (${issueIds.joinToString(",") { "?" }})
                ORDER BY created_date DESC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                var parameterIndex = 1
                issueIds.forEach { issueId ->
                    statement.setString(parameterIndex, issueId)
                    parameterIndex++
                }
                statement.setInt(parameterIndex, limit)
                parameterIndex++
                statement.setInt(parameterIndex, offset)
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        comments.add(resultSet.toIssueComment())
                    }
                }
            }
        }
        return comments
    }

    override fun getCommentsByIssueId(issueId: String): List<IssueComment> {
        val comments = mutableListOf<IssueComment>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM issue_comments WHERE issue_id = ?").use { statement ->
                statement.setString(1, issueId)
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        comments.add(resultSet.toIssueComment())
                    }
                }
            }
        }
        return comments
    }

    override fun getCommentById(commentId: String): IssueComment? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM issue_comments WHERE id = ?").use { statement ->
                statement.setString(1, commentId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        return resultSet.toIssueComment()
                    }
                }
            }
        }
        return null
    }

    private fun ResultSet.toIssueComment(): IssueComment {
        return IssueComment(
            id = getString("id"),
            createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
            rawDataParams = getString("_raw_data_params"),
            rawDataTable = getString("_raw_data_table"),
            rawDataId = getLong("_raw_data_id"),
            rawDataRemark = getString("_raw_data_remark"),
            issueId = getString("issue_id"),
            body = getString("body"),
            accountId = getString("account_id"),
            createdDate = getTimestamp("created_date")?.toInstant()?.toKotlinInstant(),
            updatedDate = getTimestamp("updated_date")?.toInstant()?.toKotlinInstant()
        )
    }
}
