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
     * Retrieves the count of pull requests that a specific user has reviewed
     * within a given date range.
     *
     * This counts unique pull requests that have at least one review by the user
     * in the specified organizations and time period.
     *
     * @param gitHubUserId The GitHub username
     * @param organizationIds The GitHub organizations to search within
     * @param startDate The start date of the range (inclusive)
     * @param endDate The end date of the range (inclusive)
     * @return The number of pull requests reviewed by the user
     */
    suspend fun getPullRequestReviewCount(
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

    /**
     * Lists notifications for the authenticated user.
     * Note: GitHub's REST API only supports the authenticated user's notifications.
     */
    suspend fun listNotifications(): List<Notification>

    /**
     * Fetch a pull request by its API URL.
     * Example URL: https://api.github.com/repos/{owner}/{repo}/pulls/{number}
     */
    suspend fun getPullRequestByUrl(url: String): PullRequest

    /**
     * Submit an approval review for a pull request by its API URL.
     * @param url API URL of the PR (e.g., https://api.github.com/repos/{owner}/{repo}/pulls/{number})
     * @param body Optional body/comment for the review
     */
    suspend fun approvePullRequestByUrl(url: String, body: String? = null)

    /**
     * Marks a notification thread as read (done) for the authenticated user.
     * @param threadId The notification thread ID.
     */
    suspend fun markNotificationAsDone(threadId: String)

    /**
     * Unsubscribes (ignores) the authenticated user from a notification thread.
     * @param threadId The notification thread ID.
     */
    suspend fun unsubscribeFromNotification(threadId: String)

    /**
     * Checks if a pull request has any approved review.
     *
     * @param url The API URL of the pull request (e.g., https://api.github.com/repos/{owner}/{repo}/pulls/{number}).
     * @return True if the pull request has at least one approved review, otherwise false.
     */
    suspend fun hasAnyApprovedReview(url: String): Boolean

    /**
     * Retrieves open pull requests authored by a specific user across the given organizations.
     *
     * @param organizationIds The GitHub organizations to search within.
     * @param author The GitHub username of the PR author.
     * @return A list of issues representing the open pull requests.
     */
    suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue>

    /**
     * Retrieves check run results for a specific commit reference.
     *
     * @param owner The repository owner.
     * @param repo The repository name.
     * @param ref The commit SHA or branch name.
     * @return A summary of check run results.
     */
    suspend fun getCheckRunsForRef(owner: String, repo: String, ref: String): CheckRunSummary

    /**
     * Retrieves a summary of reviews for a specific pull request.
     *
     * @param owner The repository owner.
     * @param repo The repository name.
     * @param prNumber The pull request number.
     * @return A summary of review states.
     */
    suspend fun getReviewSummary(owner: String, repo: String, prNumber: Int): ReviewSummary

    /**
     * Submits a review on a pull request.
     *
     * @param prApiUrl The API URL of the pull request.
     * @param event The review event type.
     * @param body Optional body/comment for the review.
     */
    suspend fun submitReview(prApiUrl: String, event: ReviewStateValue, reviewComment: String? = null)
}
