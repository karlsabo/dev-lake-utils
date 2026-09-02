package com.github.karlsabo.github

interface GitHubPullRequestReviewApi {
    suspend fun getPullRequestByUrl(url: String): PullRequest

    suspend fun getPullRequest(
        owner: String,
        repository: String,
        number: Int,
    ): PullRequest = getPullRequestByUrl(gitHubPullRequestApiUrl(owner, repository, number))

    suspend fun approvePullRequestByUrl(url: String, body: String? = null)

    suspend fun hasAnyApprovedReview(url: String): Boolean
}

fun gitHubPullRequestApiUrl(
    owner: String,
    repository: String,
    number: Int,
): String {
    require(owner.isNotBlank()) { "owner must not be blank" }
    require(repository.isNotBlank()) { "repository must not be blank" }
    require(number > 0) { "number must be positive" }
    return "https://api.github.com/repos/$owner/$repository/pulls/$number"
}
