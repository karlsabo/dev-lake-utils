package com.github.karlsabo.linear.query

import kotlinx.datetime.Instant

/**
 * Builder for Linear GraphQL queries and filters.
 * Centralizes query construction logic for the Linear API.
 */
class LinearQueryBuilder(
    private val defaultPageSize: Int = 100,
) {
    /**
     * Builds a query to fetch multiple issues by their IDs.
     */
    fun issuesByIds(issueKeys: List<String>, issueFields: String): String {
        return buildString {
            append("query {")
            issueKeys.forEachIndexed { index, issueKey ->
                val escaped = escapeGraphQlString(issueKey)
                append("\n  issue$index: issue(id: \"")
                append(escaped)
                append("\") {")
                append("\n")
                append(issueFields.indent("    "))
                append("\n  }")
            }
            append("\n}")
        }
    }

    /**
     * Builds a query to fetch child issues of a parent issue.
     */
    fun childrenOf(issueKey: String, issueFields: String, cursor: String? = null): String {
        return buildString {
            append("query {")
            append("\n  issue(id: \"")
            append(issueKey)
            append("\") {")
            append("\n    children(first: ")
            append(defaultPageSize)
            appendCursor(cursor)
            append(") {")
            append("\n      nodes {")
            append("\n")
            append(issueFields.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    /**
     * Builds a query to fetch comments for an issue.
     */
    fun issueComments(
        issueKey: String,
        commentFields: String,
        pageSize: Int = defaultPageSize,
        cursor: String? = null,
    ): String {
        return buildString {
            append("query {")
            append("\n  issue(id: \"")
            append(issueKey)
            append("\") {")
            append("\n    comments(first: ")
            append(pageSize)
            appendCursor(cursor)
            append(", orderBy: updatedAt) {")
            append("\n      nodes {")
            append("\n")
            append(commentFields.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    /**
     * Builds a query to fetch issues by filter.
     */
    fun issuesByFilter(
        filter: String,
        selection: String,
        cursor: String? = null,
        orderBy: String? = null,
    ): String {
        return buildString {
            append("query {")
            append("\n  issues(first: ")
            append(defaultPageSize)
            appendCursor(cursor)
            if (!orderBy.isNullOrBlank()) {
                append(", orderBy: ")
                append(orderBy)
            }
            append(", filter: ")
            append(filter)
            append(") {")
            append("\n    nodes {")
            append("\n")
            append(selection.indent("      "))
            append("\n    }")
            append("\n    pageInfo { hasNextPage endCursor }")
            append("\n  }")
            append("\n}")
        }
    }

    /**
     * Builds a query to fetch project milestones.
     */
    fun projectMilestones(projectId: String, milestoneFields: String, cursor: String? = null): String {
        return buildString {
            append("query {")
            append("\n  project(id: \"")
            append(projectId)
            append("\") {")
            append("\n    projectMilestones(first: ")
            append(defaultPageSize)
            appendCursor(cursor)
            append(") {")
            append("\n      nodes {")
            append("\n")
            append(milestoneFields.indent("        "))
            append("\n      }")
            append("\n      pageInfo { hasNextPage endCursor }")
            append("\n    }")
            append("\n  }")
            append("\n}")
        }
    }

    /**
     * Builds a filter for resolved issues by user within a date range.
     */
    fun resolvedIssuesFilter(userId: String, startDate: Instant, endDate: Instant): String {
        val escapedUserId = escapeGraphQlString(userId)
        val start = escapeGraphQlString(startDate.toString())
        val end = escapeGraphQlString(endDate.toString())

        return """
            {
              assignee: { id: { eq: "$escapedUserId" } }
              completedAt: { gte: "$start", lte: "$end" }
            }
        """.trimIndent()
    }

    /**
     * Builds a filter for issues with specific labels.
     */
    fun labelsFilter(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): String {
        val labelNames = labels.joinToString(", ") { "\"${escapeGraphQlString(it)}\"" }
        val completedFilter = completedAtFilter(completedAfter, completedBefore)

        return buildString {
            append("{ labels: { name: { in: [$labelNames] } }")
            if (completedFilter.isNotEmpty()) {
                append(", $completedFilter")
            }
            append(" }")
        }
    }

    /**
     * Builds a filter for issues belonging to a milestone.
     */
    fun milestoneIssuesFilter(milestoneId: String): String {
        val escapedMilestoneId = escapeGraphQlString(milestoneId)
        return "{ projectMilestone: { id: { eq: \"$escapedMilestoneId\" } } }"
    }

    /**
     * Builds a completedAt date range filter.
     */
    fun completedAtFilter(completedAfter: Instant?, completedBefore: Instant?): String {
        if (completedAfter == null && completedBefore == null) return ""

        return buildString {
            append("completedAt: { ")
            val filters = mutableListOf<String>()
            if (completedAfter != null) {
                filters.add("gte: \"${escapeGraphQlString(completedAfter.toString())}\"")
            }
            if (completedBefore != null) {
                filters.add("lte: \"${escapeGraphQlString(completedBefore.toString())}\"")
            }
            append(filters.joinToString(", "))
            append(" }")
        }
    }

    private fun StringBuilder.appendCursor(cursor: String?) {
        if (!cursor.isNullOrBlank()) {
            append(", after: \"")
            append(escapeGraphQlString(cursor))
            append("\"")
        }
    }

    companion object {
        /**
         * Escapes a string for use in GraphQL queries.
         */
        fun escapeGraphQlString(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        /**
         * Indents each non-blank line with the given prefix.
         */
        fun String.indent(prefix: String): String {
            return this.lines().joinToString("\n") { line -> if (line.isBlank()) line else prefix + line }
        }
    }
}
