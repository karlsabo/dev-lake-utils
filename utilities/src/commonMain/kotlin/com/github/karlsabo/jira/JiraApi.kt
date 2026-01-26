package com.github.karlsabo.jira

import kotlinx.datetime.Instant

interface JiraApi {
    suspend fun runJql(jql: String): List<Issue>
    suspend fun getIssues(issueKeys: List<String>): List<Issue>
    suspend fun getChildIssues(issueKeys: List<String>): List<Issue>
    suspend fun getDirectChildIssues(parentKey: String): List<Issue>
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment>
    suspend fun getIssuesResolved(userJiraId: String, startDate: Instant, endDate: Instant): List<Issue>
    suspend fun getIssuesResolvedCount(userJiraId: String, startDate: Instant, endDate: Instant): UInt
    suspend fun getIssuesByCustomFieldFilter(
        issueTypes: List<String>,
        customFieldFilter: CustomFieldFilter,
        resolvedAfter: Instant? = null,
        resolvedBefore: Instant? = null,
    ): List<Issue>
}
