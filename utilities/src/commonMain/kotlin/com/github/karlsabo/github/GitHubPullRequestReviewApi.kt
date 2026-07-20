package com.github.karlsabo.github

interface GitHubPullRequestReviewApi {
    suspend fun getPullRequestByUrl(url: String): PullRequest

    suspend fun approvePullRequestByUrl(url: String, body: String? = null)

    suspend fun hasAnyApprovedReview(url: String): Boolean
}
