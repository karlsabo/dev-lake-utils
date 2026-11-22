package com.github.karlsabo.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface GitLabMergeRequestApi {
    /** Validates the configured token against the instance and identifies the authenticated account. */
    suspend fun getAuthenticatedUser(): GitLabUser

    suspend fun getAuthoredOpenMergeRequests(): List<GitLabMergeRequest>

    /** Releases transport resources; fakes may ignore this. */
    fun close() = Unit
}

@Serializable
data class GitLabUser(
    val id: Long,
    val username: String,
)

@Serializable
data class GitLabMergeRequestReferences(
    val full: String? = null,
)

@Serializable
data class GitLabMergeRequest(
    val iid: Int,
    val title: String,
    @SerialName("web_url")
    val webUrl: String,
    val state: String = "opened",
    val draft: Boolean = false,
    @SerialName("work_in_progress")
    val workInProgress: Boolean = false,
    @SerialName("source_branch")
    val sourceBranch: String? = null,
    @SerialName("target_branch")
    val targetBranch: String? = null,
    val references: GitLabMergeRequestReferences? = null,
)

val GitLabMergeRequest.isDraft: Boolean get() = draft || workInProgress

/**
 * Resolves the project path used for display and GitHub-style repository matching. API responses normally carry
 * `references.full`, while the web URL is the fallback for fixtures or future API versions.
 */
fun GitLabMergeRequest.repositoryPath(): String {
    references?.full?.let { full ->
        val referenceSeparator = full.indexOf('!')
        if (referenceSeparator > 0) return full.substring(0, referenceSeparator)
    }
    return webUrl.substringAfter("://").substringAfter('/').substringBefore("/-/merge_requests/")
}
