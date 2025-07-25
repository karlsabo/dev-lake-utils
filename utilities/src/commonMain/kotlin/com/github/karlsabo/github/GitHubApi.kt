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
    ): List<Issue>

    /**
     * Searches for pull requests containing the specified text within a given date range and organization scope.
     *
     * @param searchText The text to search for in pull request titles or descriptions.
     * @param organizationIds A list of organization IDs to limit the search scope.
     * @param startDateInclusive The start date of the search range (inclusive).
     * @param endDateInclusive The end date of the search range (inclusive).
     * @return A list of issues representing the pull requests that match the search criteria.
     */
    suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue>
}
