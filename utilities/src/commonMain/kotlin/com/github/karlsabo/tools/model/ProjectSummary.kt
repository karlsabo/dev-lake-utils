package com.github.karlsabo.tools.model

import com.github.karlsabo.dto.Project
import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.serialization.Serializable

/**
 * Represents a summarized view of a project's details and related data.
 *
 * @property project The project associated with this summary.
 * @property durationProgressSummary A textual representation of the project's progress over time.
 * @property issues A set of issues related to the project.
 * @property durationIssues A subset of issues filtered by duration.
 * @property durationMergedPullRequests A set of pull requests that were merged within a specific duration.
 * @property milestones A set of milestones relevant to the project.
 */
@Serializable
data class ProjectSummary(
    val project: Project,
    val durationProgressSummary: String,
    val issues: Set<ProjectIssue>,
    val durationIssues: Set<ProjectIssue>,
    val durationMergedPullRequests: Set<com.github.karlsabo.github.Issue>,
    val milestones: Set<Milestone>,
    val isTagMilestoneAssignees: Boolean,
)
