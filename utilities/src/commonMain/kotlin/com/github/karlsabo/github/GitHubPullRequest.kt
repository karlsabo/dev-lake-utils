package com.github.karlsabo.github

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a GitHub Pull Request with its details.
 */
@Serializable
data class GitHubPullRequest(
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val htmlUrl: String,
    val state: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant? = null,
    val mergedAt: Instant? = null,
    val user: GitHubUser,
    val body: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
    val repository: GitHubRepository,
)

/**
 * Represents a GitHub user.
 */
@Serializable
data class GitHubUser(
    val id: String,
    val login: String,
    val avatarUrl: String? = null,
    val url: String,
)

/**
 * Represents a GitHub repository.
 */
@Serializable
data class GitHubRepository(
    val id: String,
    val name: String,
    val fullName: String,
    val url: String,
    val htmlUrl: String,
)
