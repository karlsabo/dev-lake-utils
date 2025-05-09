package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import java.sql.ResultSet
import javax.sql.DataSource

class IssueChangelogAccessorDb(private val source: DataSource) : IssueChangelogAccessor {
    override fun getChangelogsByIssueId(issueId: String): List<IssueChangelog> {
        val sql = """
            SELECT * FROM issue_changelogs
            WHERE issue_id = ?
            ORDER BY created_date DESC
        """.trimIndent()

        source.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, issueId)
                val rs = stmt.executeQuery()
                return buildIssueChangelogList(rs)
            }
        }
    }

    override fun getChangelogById(changelogId: String): IssueChangelog? {
        val sql = "SELECT * FROM issue_changelogs WHERE id = ?"
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, changelogId)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.toIssueChangelog() else null
            }
        }
    }

    override fun getPaginatedChangelogsByIssueIds(
        issueIds: Set<String>,
        limit: Int,
        offset: Int
    ): List<IssueChangelog> {
        if (issueIds.isEmpty()) return emptyList()
        val sql = """
            SELECT * FROM issue_changelogs
            WHERE issue_id IN (${issueIds.joinToString(",") { "?" }})
            ORDER BY created_date DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                var index = 1
                for (id in issueIds) {
                    stmt.setString(index++, id)
                }
                stmt.setInt(index++, limit)
                stmt.setInt(index, offset)
                val rs = stmt.executeQuery()
                return buildIssueChangelogList(rs)
            }
        }
    }

    private fun buildIssueChangelogList(rs: ResultSet): List<IssueChangelog> {
        val changelogs = mutableListOf<IssueChangelog>()
        while (rs.next()) {
            changelogs.add(rs.toIssueChangelog())
        }
        return changelogs
    }
}

private fun ResultSet.toIssueChangelog(): IssueChangelog =
    IssueChangelog(
        id = getString("id")!!,
        createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
        updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
        rawDataParams = getString("_raw_data_params"),
        rawDataTable = getString("_raw_data_table"),
        rawDataId = getLong("_raw_data_id"),
        rawDataRemark = getString("_raw_data_remark"),
        issueId = getString("issue_id"),
        authorId = getString("author_id"),
        authorName = getString("author_name"),
        fieldId = getString("field_id"),
        fieldName = getString("field_name"),
        originalFromValue = getString("original_from_value"),
        originalToValue = getString("original_to_value"),
        fromValue = getString("from_value"),
        toValue = getString("to_value"),
        createdDate = getTimestamp("created_date")?.toInstant()?.toKotlinInstant(),
    )
