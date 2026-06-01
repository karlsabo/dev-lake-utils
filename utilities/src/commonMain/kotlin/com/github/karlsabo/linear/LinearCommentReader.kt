package com.github.karlsabo.linear

import com.github.karlsabo.common.pagination.collectCursorPaginatedWithLimit
import com.github.karlsabo.common.pagination.extractCursorPage
import com.github.karlsabo.linear.conversion.toProjectComment
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.linear.query.LinearQueryBuilder.Companion.escapeGraphQlString
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.tools.lenientJson

internal class LinearCommentReader(
    private val graphQlClient: LinearGraphQlClient,
    private val queryBuilder: LinearQueryBuilder,
) {
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
        if (maxResults <= 0) return emptyList()

        val escapedIssueKey = escapeGraphQlString(issueKey)
        return collectCursorPaginatedWithLimit(
            maxItems = maxResults,
            fetchPage = { cursor, pageSize ->
                graphQlClient.execute(
                    queryBuilder.issueComments(escapedIssueKey, LINEAR_COMMENT_FIELDS, pageSize, cursor),
                )
            },
            extractPage = { data -> data.extractCursorPage("issue", "comments") },
            transform = { node -> lenientJson.decodeFromJsonElement(Comment.serializer(), node).toProjectComment() },
        )
    }
}
