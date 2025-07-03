package com.github.karlsabo.github

import kotlinx.datetime.Instant

/**
 * Interface for interacting with the GitHub API.
 */
interface GitHubApi {
    /**
     * Retrieves the count of pull requests merged by a specific user within a given date range.
     *
     * @param gitHubUserId The GitHub username
     * @param startDate The start date of the range (inclusive)
     * @param endDate The end date of the range (inclusive)
     * @return The number of merged pull requests
     */
    suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt

    /**
     * Gets the details of pull requests by a user that have been merged within a specified date range.
     *
     * @param gitHubUserId The GitHub user ID
     * @param startDate The start date (inclusive)
     * @param endDate The end date (inclusive)
     * @return List of pull request details
     */
    suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<GitHubPullRequest>

    /**
     * Retrieves the details of a pull request from the specified URL.
     *
     * @param prUrl The URL of the pull request to fetch details for.
     */
    suspend fun getPullRequestDetails(
        prUrl: String,
    ): GitHubPullRequest

    /**
     * Retrieves the details of a pull request using the PR ID.
     *
     * @param owner The owner of the repository
     * @param repo The name of the repository
     * @param prNumber The number of the pull request to fetch details for
     */
    suspend fun getPullRequestDetails(
        owner: String,
        repo: String,
        prNumber: Int,
    ): GitHubPullRequest
}
