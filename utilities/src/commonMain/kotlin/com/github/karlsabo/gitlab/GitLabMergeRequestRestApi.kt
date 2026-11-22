package com.github.karlsabo.gitlab

import com.github.karlsabo.tools.lenientJson
import io.ktor.client.statement.bodyAsText

internal class GitLabMergeRequestRestApi(
    private val restClient: GitLabRestClient,
    private val apiBaseUrl: String,
) : GitLabMergeRequestApi {
    override suspend fun getAuthenticatedUser(): GitLabUser {
        val response = restClient.getResponse("$apiBaseUrl/user", "validate GitLab account")
        return lenientJson.decodeFromString(GitLabUser.serializer(), response.bodyAsText())
    }

    override suspend fun getAuthoredOpenMergeRequests(): List<GitLabMergeRequest> {
        val mergeRequests = mutableListOf<GitLabMergeRequest>()
        var page = 1
        while (true) {
            val response = restClient.getResponse(
                authoredOpenMergeRequestsUrl(apiBaseUrl, page),
                "list authored merge requests",
            )
            val items = lenientJson.decodeFromString<List<GitLabMergeRequest>>(response.bodyAsText())
            mergeRequests += items
            val nextPage = response.headers[GITLAB_NEXT_PAGE_HEADER]?.toIntOrNull()
            val hasMorePages = nextPage?.let { it > page } ?: (items.size >= GITLAB_PER_PAGE)
            if (!hasMorePages) break
            page = nextPage ?: (page + 1)
        }
        return mergeRequests
    }
}
