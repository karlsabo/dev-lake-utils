package com.github.karlsabo.linear

import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.projectmanagement.ProjectComment
import io.ktor.client.HttpClient

/** Reads the project- and label-scoped Linear issue union, including terminal issues for lifecycle tracking. */
class LinearTriageReader(
    config: LinearApiRestConfig,
    clientOverride: HttpClient? = null,
) {
    private val graphQlClient = LinearGraphQlClient(config, clientOverride)
    private val queryBuilder = LinearQueryBuilder(LINEAR_DEFAULT_PAGE_SIZE)
    private val issueReader = LinearIssueReader(graphQlClient, queryBuilder)
    private val commentReader = LinearCommentReader(graphQlClient, queryBuilder)

    suspend fun getIssues(
        teamKey: String,
        projectName: String?,
        labelName: String?,
    ): List<LinearTriageIssue> = issueReader.getTriageIssues(teamKey, projectName, labelName)

    suspend fun getRecentComments(
        issueKey: String,
        maxResults: Int,
    ): List<ProjectComment> = commentReader.getRecentComments(issueKey, maxResults)
}
