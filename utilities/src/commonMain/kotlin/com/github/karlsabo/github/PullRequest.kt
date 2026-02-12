package com.github.karlsabo.github

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PullRequestHead(
    val ref: String? = null,
    val sha: String? = null,
)

@Serializable
data class PullRequest(
    val url: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null,
    /** open or closed */
    val state: String? = null,
    @SerialName("merged_at")
    val mergedAt: Instant? = null,
    val head: PullRequestHead? = null,
)

val PullRequest.isMerged: Boolean get() = mergedAt != null
val PullRequest.isClosed: Boolean get() = !isMerged && state?.equals("closed", ignoreCase = true) == true
val PullRequest.isOpen: Boolean get() = !isMerged && !isClosed
