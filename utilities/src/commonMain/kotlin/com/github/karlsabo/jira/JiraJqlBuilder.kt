package com.github.karlsabo.jira

import com.github.karlsabo.common.datetime.DateTimeFormatting.toCompactUtcDateTime
import com.github.karlsabo.projectmanagement.IssueFilter
import kotlin.time.Instant

internal object JiraJqlBuilder {
    fun resolvedIssues(
        userId: String,
        startDate: Instant,
        endDate: Instant,
    ): String = "assignee = $userId AND resolutiondate >= \"${startDate.toCompactUtcDateTime()}\" " +
        "AND resolutiondate <= \"${endDate.toCompactUtcDateTime()}\""

    fun issueFilter(filter: IssueFilter): String {
        val conditions = mutableListOf<String>()

        if (!filter.issueTypes.isNullOrEmpty()) {
            val types = filter.issueTypes.joinToString(", ") { "\"$it\"" }
            conditions.add("issuetype IN ($types)")
        }

        if (filter.completedAfter != null) {
            conditions.add("resolved >= \"${filter.completedAfter}\"")
        }

        if (filter.completedBefore != null) {
            conditions.add("resolved <= \"${filter.completedBefore}\"")
        }

        return conditions.joinToString(" AND ")
    }

    fun customFieldIssueQuery(
        issueTypes: List<String>,
        customFieldFilter: CustomFieldFilter,
        resolvedAfter: Instant? = null,
        resolvedBefore: Instant? = null,
    ): String = buildString {
        append("issuetype in (${issueTypes.joinToString(", ")})")
        if (resolvedAfter != null) {
            append(" AND resolutiondate >= \"${resolvedAfter.toCompactUtcDateTime()}\"")
        }
        if (resolvedBefore != null) {
            append(" AND resolutiondate <= \"${resolvedBefore.toCompactUtcDateTime()}\"")
        }
        appendCustomFieldFilter(customFieldFilter)
        append(" ORDER BY resolutiondate DESC")
    }
}

private fun StringBuilder.appendCustomFieldFilter(customFieldFilter: CustomFieldFilter) {
    val values = customFieldFilter.values.joinToString(", ") { "\"$it\"" }
    append(" AND \"${customFieldFilter.fieldId}\" in ($values)")
}
