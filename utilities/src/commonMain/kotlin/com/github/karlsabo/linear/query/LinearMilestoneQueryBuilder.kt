package com.github.karlsabo.linear.query

internal class LinearMilestoneQueryBuilder(
    private val defaultPageSize: Int,
) {
    fun projectMilestones(
        projectId: String,
        milestoneFields: String,
        cursor: String? = null,
    ): String = buildString {
        append("query {")
        append("\n  project(id: \"")
        append(projectId)
        append("\") {")
        append("\n    projectMilestones(first: ")
        append(defaultPageSize)
        appendLinearCursor(cursor)
        append(") {")
        append("\n      nodes {")
        append("\n")
        append(milestoneFields.indentGraphQl("        "))
        append("\n      }")
        append("\n      pageInfo { hasNextPage endCursor }")
        append("\n    }")
        append("\n  }")
        append("\n}")
    }
}
