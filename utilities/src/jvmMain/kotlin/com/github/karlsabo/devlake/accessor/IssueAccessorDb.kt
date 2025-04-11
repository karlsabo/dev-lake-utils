package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import java.sql.Date
import java.sql.ResultSet
import javax.sql.DataSource

class IssueAccessorDb(private val dataSource: DataSource) : IssueAccessor {
    override fun getIssues(limit: Int, offset: Int): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement("SELECT * FROM issues LIMIT ? OFFSET ?")
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesByKey(issueKey: String): Issue? {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement("SELECT * FROM issues WHERE issue_key = ?")
            statement.setString(1, issueKey)
            val resultSet = statement.executeQuery()
            return if (resultSet.next()) resultSet.toIssue() else null
        }
    }

    override fun getIssuesByKey(issueKeys: List<String>): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement(
                "SELECT * FROM issues WHERE issue_key IN (${issueKeys.joinToString(",") { "?" }})"
            )
            issueKeys.forEachIndexed { index, key ->
                statement.setString(index + 1, key)
            }
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesById(issueIds: List<String>): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement(
                "SELECT * FROM issues WHERE ID IN (${issueIds.joinToString(",") { "?" }})"
            )
            issueIds.forEachIndexed { index, key ->
                statement.setString(index + 1, key)
            }
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesByCreatorId(creatorId: String): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement("SELECT * FROM issues WHERE creator_id = ?")
            statement.setString(1, creatorId)
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesByAssigneeId(assigneeId: String): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement("SELECT * FROM issues WHERE assignee_id = ?")
            statement.setString(1, assigneeId)
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesByAssigneeIdAndAfterResolutionDate(assigneeId: String, resolutionDate: Instant): List<Issue> {
        dataSource.connection.use { connection ->
            val statement =
                connection.prepareStatement("SELECT * FROM issues WHERE assignee_id = ? AND resolution_date >= ?")
            statement.setString(1, assigneeId)
            statement.setDate(2, Date(resolutionDate.toEpochMilliseconds()))
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getIssuesByParentIssueId(parentIssueId: String): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement("SELECT * FROM issues WHERE parent_issue_id = ?")
            statement.setString(1, parentIssueId)
            val resultSet = statement.executeQuery()
            val issues = mutableListOf<Issue>()
            while (resultSet.next()) {
                issues.add(resultSet.toIssue())
            }
            return issues
        }
    }

    override fun getAllChildIssues(issueIds: List<String>): Set<Issue> {
        val childIssues = mutableSetOf<Issue>()
        var parentIssueIds: List<String> = issueIds
        while (parentIssueIds.isNotEmpty()) {
            println("Getting child issues for $parentIssueIds")
            val issues = getChildIssues(parentIssueIds)
            childIssues.addAll(issues)
            parentIssueIds = issues.filter { !it.isIssueOrBug() }.map { it.id }
        }

        return childIssues
    }


    override fun getChildIssues(issueIds: List<String>): List<Issue> {
        dataSource.connection.use { connection ->
            val statement = connection.prepareStatement(
                "SELECT * FROM issues WHERE parent_issue_id IN (${issueIds.joinToString(",") { "?" }})"
            )
            issueIds.forEachIndexed { index, id ->
                statement.setString(index + 1, id)
            }
            val resultSet = statement.executeQuery()
            val childIssues = mutableListOf<Issue>()
            while (resultSet.next()) {
                childIssues.add(resultSet.toIssue())
            }
            return childIssues
        }
    }

    private fun ResultSet.toIssue(): Issue {
        return Issue(
            id = getString("id"),
            createdAt = getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
            rawDataParams = getString("_raw_data_params"),
            rawDataTable = getString("_raw_data_table"),
            rawDataId = getLong("_raw_data_id"),
            rawDataRemark = getString("_raw_data_remark"),
            url = getString("url"),
            iconUrl = getString("icon_url"),
            issueKey = getString("issue_key"),
            title = getString("title"),
            description = getString("description"),
            epicKey = getString("epic_key"),
            type = getString("type"),
            originalType = getString("original_type"),
            status = getString("status"),
            originalStatus = getString("original_status"),
            resolutionDate = getTimestamp("resolution_date")?.toInstant()?.toKotlinInstant(),
            createdDate = getTimestamp("created_date")?.toInstant()?.toKotlinInstant(),
            updatedDate = getTimestamp("updated_date")?.toInstant()?.toKotlinInstant(),
            leadTimeMinutes = getLong("lead_time_minutes"),
            parentIssueId = getString("parent_issue_id"),
            priority = getString("priority"),
            storyPoint = getDouble("story_point"),
            originalEstimateMinutes = getLong("original_estimate_minutes"),
            timeSpentMinutes = getLong("time_spent_minutes"),
            timeRemainingMinutes = getLong("time_remaining_minutes"),
            creatorId = getString("creator_id"),
            creatorName = getString("creator_name"),
            assigneeId = getString("assignee_id"),
            assigneeName = getString("assignee_name"),
            severity = getString("severity"),
            component = getString("component"),
            originalProject = getString("original_project"),
            urgency = getString("urgency"),
            isSubtask = getBoolean("is_subtask")
        )
    }
}
