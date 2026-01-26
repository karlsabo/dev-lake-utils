package com.github.karlsabo.tools.model

import com.github.karlsabo.dto.User
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.serialization.Serializable

@Serializable
data class Milestone(
    val assignee: User?,
    val issue: ProjectIssue,
    val issues: Set<ProjectIssue>,
    val milestoneComments: Set<ProjectComment>,
    val durationIssues: Set<ProjectIssue>,
    val durationMergedPullRequests: Set<com.github.karlsabo.github.Issue>,
)
