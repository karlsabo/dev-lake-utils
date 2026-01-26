package com.github.karlsabo.projectmanagement

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A unified representation of an issue across different project management systems (Jira, Linear, etc.).
 */
@Serializable
data class ProjectIssue(
    val id: String,
    val key: String,
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val statusCategory: StatusCategory? = null,
    val issueType: String? = null,
    val priority: String? = null,
    val estimate: Double? = null,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val creatorId: String? = null,
    val creatorName: String? = null,
    val parentKey: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
    val dueDate: Instant? = null,
)

/**
 * Returns true if this issue is completed (has a completedAt date).
 */
fun ProjectIssue.isCompleted(): Boolean = completedAt != null

/**
 * Returns true if this issue represents a milestone (epic in Jira terms).
 */
fun ProjectIssue.isMilestone(): Boolean {
    val type = issueType?.lowercase() ?: return false
    return type == "epic" || type == "milestone"
}

/**
 * Returns true if this issue is a regular issue or bug (not a milestone/epic).
 */
fun ProjectIssue.isIssueOrBug(): Boolean {
    val type = issueType?.lowercase() ?: return false
    return when (type) {
        "bug", "issue", "story", "subtask", "artifact", "task", "vulnerability",
        "request", "design story", "ds story", "change request",
            -> true

        "epic", "theme", "parent artifact", "r&d initiative", "sub-task",
        "company initiative", "milestone",
            -> false

        else -> true // Default to treating unknown types as issues
    }
}
