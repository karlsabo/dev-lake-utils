package com.github.karlsabo.projectmanagement

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A unified representation of a milestone across different project management systems.
 *
 * In Jira, Epics are treated as milestones.
 * In Linear, ProjectMilestones are native entities separate from Issues.
 */
@Serializable
data class ProjectMilestone(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val targetDate: Instant? = null,
    val progress: Double? = null,  // 0.0 to 1.0
    val status: String? = null,
    val projectId: String? = null,
)
