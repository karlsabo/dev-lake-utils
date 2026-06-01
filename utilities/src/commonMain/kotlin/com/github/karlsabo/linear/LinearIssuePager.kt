package com.github.karlsabo.linear

import com.github.karlsabo.common.pagination.collectCursorPaginated
import com.github.karlsabo.common.pagination.extractCursorPage
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.linear.query.LinearQueryBuilder.Companion.escapeGraphQlString
import com.github.karlsabo.tools.lenientJson
import kotlinx.serialization.json.JsonElement

internal class LinearIssuePager(
    private val graphQlClient: LinearGraphQlClient,
    private val queryBuilder: LinearQueryBuilder,
) {
    suspend fun fetchProjectIssues(projectId: String): List<Issue> = collectCursorPaginated(
        fetchPage = { cursor ->
            graphQlClient.execute(queryBuilder.projectIssues(projectId, LINEAR_ISSUE_FIELDS, cursor))
        },
        extractPage = { data -> data.extractCursorPage("project", "issues") },
        transform = ::decodeIssue,
    )

    suspend fun fetchChildIssues(issueKey: String): List<Issue> {
        val escapedIssueKey = escapeGraphQlString(issueKey)
        return collectCursorPaginated(
            fetchPage = { cursor ->
                graphQlClient.execute(queryBuilder.childrenOf(escapedIssueKey, LINEAR_ISSUE_FIELDS, cursor))
            },
            extractPage = { data -> data.extractCursorPage("issue", "children") },
            transform = ::decodeIssue,
        )
    }

    suspend fun fetchIssuesByFilter(
        filter: String,
        selection: String,
        orderBy: String? = null,
    ): List<Issue> = collectCursorPaginated(
        fetchPage = { cursor ->
            graphQlClient.execute(queryBuilder.issuesByFilter(filter, selection, cursor, orderBy))
        },
        extractPage = { data -> data.extractCursorPage("issues") },
        transform = ::decodeIssue,
    )

    suspend fun countIssuesByFilter(filter: String): UInt = collectCursorPaginated(
        fetchPage = { cursor ->
            graphQlClient.execute(queryBuilder.issuesByFilter(filter, LINEAR_ISSUE_ID_FIELDS, cursor, null))
        },
        extractPage = { data -> data.extractCursorPage("issues") },
        transform = { },
    ).size.toUInt()
}

private fun decodeIssue(node: JsonElement): Issue = lenientJson.decodeFromJsonElement(Issue.serializer(), node)
