package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration

interface PullRequestAccessor {
    fun getPullRequestById(id: String): PullRequest?
    fun getPullRequestByAuthorId(authorId: String): List<PullRequest>
    fun getPullRequestsByAuthorIdAndAfterMergedDate(
        authorId: String,
        mergedDateAfterInclusive: Instant
    ): List<PullRequest>

    /**
     * Retrieves a list of pull requests that have been created since a specified duration and are associated with the given issue keys.
     *
     * @param issueKeys A list of issue keys to filter the pull requests.
     * @param sinceInclusive The duration from the current time to filter the pull requests.
     * @return A list of pull requests that match the criteria.
     */
    fun getPullRequestsMergedSinceWithIssueKey(issueKeys: List<String>, sinceInclusive: Duration): List<PullRequest>
}

@Serializable
data class PullRequest(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val baseRepoId: String? = null,
    val baseRef: String? = null,
    val baseCommitSha: String? = null,
    val headRepoId: String? = null,
    val headRef: String? = null,
    val headCommitSha: String? = null,
    val mergeCommitSha: String? = null,
    val status: String? = null,
    val originalStatus: String? = null,
    val type: String? = null,
    val component: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val authorName: String? = null,
    val authorId: String? = null,
    val parentPrId: String? = null,
    val pullRequestKey: Long? = null,
    val createdDate: Instant? = null,
    val mergedDate: Instant? = null,
    val closedDate: Instant? = null,
    val additions: Long? = null,
    val deletions: Long? = null,
    val mergedByName: String? = null,
    val mergedById: String? = null,
    val isDraft: Boolean? = null,
)
