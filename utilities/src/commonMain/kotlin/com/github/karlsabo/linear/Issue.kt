package com.github.karlsabo.linear

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Issue(
    val id: String,
    val identifier: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
    val archivedAt: Instant? = null,
    val canceledAt: Instant? = null,
    val dueDate: String? = null,
    val priority: Int? = null,
    val estimate: Double? = null,
    val assignee: User? = null,
    val creator: User? = null,
    val state: WorkflowState? = null,
    val parent: IssueParent? = null,
)

/**
 * Returns true if this issue is completed (has a completedAt date).
 */
fun Issue.isCompleted(): Boolean = completedAt != null

/**
 * Returns false for Linear issues since milestones are separate entities in Linear.
 * Linear has ProjectMilestones as distinct objects from Issues.
 */
fun Issue.isMilestone(): Boolean = false

/**
 * Returns true for all Linear issues since they are all regular issues.
 * In Linear, milestones are separate entities (ProjectMilestones), not special issue types.
 */
fun Issue.isIssueOrBug(): Boolean = true
