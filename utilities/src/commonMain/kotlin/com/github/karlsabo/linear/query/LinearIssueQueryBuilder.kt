package com.github.karlsabo.linear.query

internal class LinearIssueQueryBuilder(
    private val defaultPageSize: Int,
) {
    fun issuesByIds(issueKeys: List<String>, issueFields: String): String = buildString {
        append("query {")
        issueKeys.forEachIndexed { index, issueKey ->
            val escaped = escapeLinearGraphQlString(issueKey)
            append("\n  issue$index: issue(id: \"")
            append(escaped)
            append("\") {")
            append("\n")
            append(issueFields.indentGraphQl("    "))
            append("\n  }")
        }
        append("\n}")
    }

    fun childrenOf(
        issueKey: String,
        issueFields: String,
        cursor: String? = null,
    ): String = buildString {
        append("query {")
        append("\n  issue(id: \"")
        append(issueKey)
        append("\") {")
        append("\n    children(first: ")
        append(defaultPageSize)
        appendLinearCursor(cursor)
        append(") {")
        append("\n      nodes {")
        append("\n")
        append(issueFields.indentGraphQl("        "))
        append("\n      }")
        append("\n      pageInfo { hasNextPage endCursor }")
        append("\n    }")
        append("\n  }")
        append("\n}")
    }

    fun issuesByFilter(
        filter: String,
        selection: String,
        cursor: String? = null,
        orderBy: String? = null,
    ): String = buildString {
        append("query {")
        append("\n  issues(first: ")
        append(defaultPageSize)
        appendLinearCursor(cursor)
        if (!orderBy.isNullOrBlank()) {
            append(", orderBy: ")
            append(orderBy)
        }
        append(", filter: ")
        append(filter)
        append(") {")
        append("\n    nodes {")
        append("\n")
        append(selection.indentGraphQl("      "))
        append("\n    }")
        append("\n    pageInfo { hasNextPage endCursor }")
        append("\n  }")
        append("\n}")
    }

    fun projectIssues(
        projectId: String,
        issueFields: String,
        cursor: String? = null,
    ): String = buildString {
        append("query {")
        append("\n  project(id: \"")
        append(escapeLinearGraphQlString(projectId))
        append("\") {")
        append("\n    issues(first: ")
        append(defaultPageSize)
        appendLinearCursor(cursor)
        append(") {")
        append("\n      nodes {")
        append("\n")
        append(issueFields.indentGraphQl("        "))
        append("\n      }")
        append("\n      pageInfo { hasNextPage endCursor }")
        append("\n    }")
        append("\n  }")
        append("\n}")
    }
}
