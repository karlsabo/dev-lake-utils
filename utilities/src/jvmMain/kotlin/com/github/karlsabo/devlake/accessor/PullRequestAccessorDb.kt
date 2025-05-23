package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Duration

class PullRequestAccessorDb(private val dataSource: DataSource) : PullRequestAccessor {
    private fun ResultSet.toPullRequest(): PullRequest {
        return PullRequest(
            id = getString("id"),
            createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
            rawDataParams = getString("_raw_data_params"),
            rawDataTable = getString("_raw_data_table"),
            rawDataId = getLong("_raw_data_id"),
            rawDataRemark = getString("_raw_data_remark"),
            baseRepoId = getString("base_repo_id"),
            baseRef = getString("base_ref"),
            baseCommitSha = getString("base_commit_sha"),
            headRepoId = getString("head_repo_id"),
            headRef = getString("head_ref"),
            headCommitSha = getString("head_commit_sha"),
            mergeCommitSha = getString("merge_commit_sha"),
            status = getString("status"),
            originalStatus = getString("original_status"),
            type = getString("type"),
            component = getString("component"),
            title = getString("title"),
            description = getString("description"),
            url = getString("url"),
            authorName = getString("author_name"),
            authorId = getString("author_id"),
            parentPrId = getString("parent_pr_id"),
            pullRequestKey = getLong("pull_request_key"),
            createdDate = getTimestamp("created_date")?.toInstant()?.toKotlinInstant(),
            mergedDate = getTimestamp("merged_date")?.toInstant()?.toKotlinInstant(),
            closedDate = getTimestamp("closed_date")?.toInstant()?.toKotlinInstant(),
            additions = getLong("additions"),
            deletions = getLong("deletions"),
            mergedByName = getString("merged_by_name"),
            mergedById = getString("merged_by_id"),
            isDraft = getBoolean("is_draft")
        )
    }

    override fun getPullRequestsMergedSinceWithIssueKey(
        issueKeys: List<String>,
        sinceInclusive: Duration
    ): List<PullRequest> {
        val pullRequests = mutableListOf<PullRequest>()
        val now = Clock.System.now()
        val pastDate = now.minus(sinceInclusive)
        val sql = StringBuilder(
            """
            SELECT 
                * 
            FROM
                pull_requests 
            WHERE
                merged_date >= ?
                AND (
            """.trimIndent()
        )

        issueKeys.forEachIndexed { index, issueKey ->
            sql.append("(head_ref LIKE ? OR title LIKE ? OR description LIKE ?)")
            if (index != issueKeys.lastIndex) sql.append(" OR ")
        }
        sql.append(")")

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql.toString()).use { statement ->
                var index = 1
                statement.setTimestamp(index++, Timestamp(pastDate.toEpochMilliseconds()))
                issueKeys.forEach { issueKey ->
                    val issueLike = "%$issueKey%"
                    statement.setString(index++, issueLike)
                    statement.setString(index++, issueLike)
                    statement.setString(index++, issueLike)
                }
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        pullRequests.add(resultSet.toPullRequest())
                    }
                }
            }
        }
        return pullRequests
    }

    override fun getPullRequestById(id: String): PullRequest? {
        val sql = "SELECT * FROM pull_requests WHERE id = ?"
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toPullRequest() else null
                }
            }
        }
    }

    override fun getPullRequestByAuthorId(authorId: String): List<PullRequest> {
        val pullRequests = mutableListOf<PullRequest>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM pull_requests WHERE author_id = ?").use { statement ->
                statement.setString(1, authorId)
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) pullRequests.add(resultSet.toPullRequest())
                }
            }
        }
        return pullRequests
    }

    override fun getPullRequestsByAuthorIdAndAfterMergedDate(
        authorId: String,
        mergedDateAfterInclusive: Instant
    ): List<PullRequest> {
        val pullRequests = mutableListOf<PullRequest>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM pull_requests WHERE author_id = ? AND merged_date >= ?")
                .use { statement ->
                    statement.setString(1, authorId)
                    statement.setDate(2, Date(mergedDateAfterInclusive.toEpochMilliseconds()))
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) pullRequests.add(resultSet.toPullRequest())
                    }
                }
        }
        return pullRequests
    }
}

