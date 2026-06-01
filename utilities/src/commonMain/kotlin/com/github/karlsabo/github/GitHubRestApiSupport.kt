package com.github.karlsabo.github

internal const val HTTP_SUCCESS_MIN = 200
internal const val HTTP_SUCCESS_MAX = 299
internal const val HTTP_NO_CONTENT = 204
internal const val HTTP_NOT_FOUND = 404
internal const val SEARCH_PER_PAGE = 20
internal const val GITHUB_SEARCH_RESULT_LIMIT = 1_000
internal const val NOTIFICATION_PER_PAGE = 50
internal const val COMMIT_STATUS_PER_PAGE = 100

internal val successStatusCodes = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX

internal class GitHubApiException(
    message: String,
) : IllegalStateException(message)

internal fun throwGitHubApiException(
    operation: String,
    statusCode: Int,
    context: String = "",
    responseText: String,
): Nothing {
    val contextSuffix = context.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    throw GitHubApiException(
        "Failed to $operation: $statusCode$contextSuffix responseText=```$responseText```",
    )
}

internal fun pageLimit(totalCount: Int): Int = (totalCount / SEARCH_PER_PAGE) + 1

internal fun organizationQualifier(
    organizationIds: List<String>,
): String = organizationIds.joinToString(" ") { "org:$it" }

internal fun searchIssuesUrl(
    encodedQuery: String,
    page: Int,
): String = "https://api.github.com/search/issues?q=$encodedQuery" +
    "&per_page=$SEARCH_PER_PAGE&page=$page"

internal fun notificationsUrl(page: Int): String = "https://api.github.com/notifications?participating=false&all=true" +
    "&per_page=$NOTIFICATION_PER_PAGE&page=$page"

internal fun reviewsUrl(url: String): String = if (url.endsWith("/reviews")) url else "$url/reviews"
