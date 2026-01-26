package com.github.karlsabo.linear

import kotlinx.serialization.Serializable

/**
 * Represents a Linear ProjectMilestone.
 *
 * In Linear, milestones are separate entities from issues, unlike Jira where Epics serve as milestones.
 * Milestones track progress toward a goal and have issues assigned to them.
 */
@Serializable
data class ProjectMilestone(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val targetDate: String? = null,
    val progress: Double? = null,
    val status: String? = null,
)
