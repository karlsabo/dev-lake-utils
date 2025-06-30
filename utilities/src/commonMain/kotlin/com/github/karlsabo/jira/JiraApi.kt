package com.github.karlsabo.jira

import kotlinx.datetime.Instant

interface JiraApi {
    suspend fun runJql(jql: String): List<Issue>
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment>
    suspend fun getIssuesResolved(userJiraId: String, startDate: Instant, endDate: Instant): List<Issue>
}
