package com.github.karlsabo.jira

interface JiraApi {
    suspend fun runJql(jql: String): List<Issue>
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment>
}