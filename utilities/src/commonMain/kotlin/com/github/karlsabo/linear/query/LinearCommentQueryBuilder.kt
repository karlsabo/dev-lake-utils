package com.github.karlsabo.linear.query

internal class LinearCommentQueryBuilder(
    private val defaultPageSize: Int,
) {
    fun issueComments(
        issueKey: String,
        commentFields: String,
        pageSize: Int = defaultPageSize,
        cursor: String? = null,
    ): String = buildString {
        append("query {")
        append("\n  issue(id: \"")
        append(issueKey)
        append("\") {")
        append("\n    comments(first: ")
        append(pageSize)
        appendLinearCursor(cursor)
        append(", orderBy: updatedAt) {")
        append("\n      nodes {")
        append("\n")
        append(commentFields.indentGraphQl("        "))
        append("\n      }")
        append("\n      pageInfo { hasNextPage endCursor }")
        append("\n    }")
        append("\n  }")
        append("\n}")
    }
}
