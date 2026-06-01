package com.github.karlsabo.jira

import com.github.karlsabo.jira.config.JiraApiRestConfig
import com.github.karlsabo.jira.conversion.toProjectComment
import com.github.karlsabo.jira.extensions.toComment
import com.github.karlsabo.projectmanagement.ProjectComment
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class JiraCommentReader(
    private val config: JiraApiRestConfig,
    private val httpApi: JiraHttpApi,
) {
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> {
        val url = "https://${config.domain}/rest/api/3/issue/$issueKey/comment?orderBy=-created&maxResults=1&startAt=0"
        val root = httpApi.getJson(url, "get comments for issueKey=$issueKey maxResults=$maxResults")
        return root["comments"]?.jsonArray
            ?.map { it.jsonObject.toComment().toProjectComment() }
            ?: emptyList()
    }
}
