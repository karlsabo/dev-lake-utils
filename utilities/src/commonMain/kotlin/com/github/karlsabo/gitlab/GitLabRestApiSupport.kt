package com.github.karlsabo.gitlab

internal const val GITLAB_PER_PAGE = 100
internal const val GITLAB_HTTP_SUCCESS_MIN = 200
internal const val GITLAB_HTTP_SUCCESS_MAX = 299
internal const val GITLAB_NEXT_PAGE_HEADER = "X-Next-Page"

internal val gitLabSuccessStatusCodes = GITLAB_HTTP_SUCCESS_MIN..GITLAB_HTTP_SUCCESS_MAX

class GitLabApiException internal constructor(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal fun throwGitLabApiException(
    operation: String,
    statusCode: Int,
    responseText: String,
): Nothing = throw GitLabApiException(
    statusCode = statusCode,
    message = "Failed to $operation: $statusCode responseText=```$responseText```",
)

internal fun authoredOpenMergeRequestsUrl(
    apiBaseUrl: String,
    page: Int,
): String = "$apiBaseUrl/merge_requests?scope=created_by_me&state=opened" +
    "&per_page=$GITLAB_PER_PAGE&page=$page"
