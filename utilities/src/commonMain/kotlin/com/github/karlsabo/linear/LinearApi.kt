package com.github.karlsabo.linear

import kotlinx.datetime.Instant

interface LinearApi {
    suspend fun getIssues(issueKeys: List<String>): List<Issue>
    suspend fun getChildIssues(issueKeys: List<String>): List<Issue>
    suspend fun getDirectChildIssues(parentKey: String): List<Issue>
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<Comment>
    suspend fun getIssuesResolved(userId: String, startDate: Instant, endDate: Instant): List<Issue>
    suspend fun getIssuesResolvedCount(userId: String, startDate: Instant, endDate: Instant): UInt
}
