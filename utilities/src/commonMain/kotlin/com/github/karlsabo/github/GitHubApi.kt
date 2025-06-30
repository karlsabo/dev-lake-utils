package com.github.karlsabo.github

import kotlinx.datetime.Instant

/**
 * Interface for interacting with the GitHub API.
 */
interface GitHubApi {
    /**
     * Gets the count of pull requests created by a user within a specified date range.
     *
     * @param username The GitHub username
     * @param startDate The start date (inclusive)
     * @param endDate The end date (inclusive)
     * @return The count of pull requests
     */
    suspend fun getPullRequestCount(username: String, startDate: Instant, endDate: Instant): Int

    /**
     * Gets the details of pull requests closed by a user within a specified date range.
     *
     * @param username The GitHub username
     * @param startDate The start date (inclusive)
     * @param endDate The end date (inclusive)
     * @return List of pull request details
     */
    suspend fun getClosedPullRequests(username: String, startDate: Instant, endDate: Instant): List<GitHubPullRequest>

    /**
     * Gets the details of pull requests by a user that have been merged within a specified date range.
     *
     * @param gitHubUserId The GitHub user ID
     * @param startDate The start date (inclusive)
     * @param endDate The end date (inclusive)
     * @return List of pull request details
     */
    suspend fun getPullRequestsByAuthorIdAndAfterMergedDate(
        gitHubUserId: String,
        startDate: Instant,
        endDate: Instant,
    ): List<GitHubPullRequest>

    /**
     * Gets the count of pull requests by a user that have been merged within a specified date range.
     *
     * @param gitHubUserId The GitHub user ID
     * @param startDate The start date (inclusive)
     * @param endDate The end date (inclusive)
     * @return The count of pull requests as UInt
     */
    suspend fun getPullRequestsByAuthorIdAndAfterMergedDateCount(
        gitHubUserId: String,
        startDate: Instant,
        endDate: Instant,
    ): UInt
}
