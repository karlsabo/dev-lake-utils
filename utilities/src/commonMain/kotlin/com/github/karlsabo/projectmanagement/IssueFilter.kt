package com.github.karlsabo.projectmanagement

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A generic filter for querying issues across different project management systems.
 *
 * Different backends will interpret these filters according to their capabilities:
 * - Jira: Maps to JQL with custom field filters
 * - Linear: Maps to GraphQL filters with labels
 */
@Serializable
data class IssueFilter(
    val issueTypes: List<String>? = null,
    val labels: List<String>? = null,
    val completedAfter: Instant? = null,
    val completedBefore: Instant? = null,
)
