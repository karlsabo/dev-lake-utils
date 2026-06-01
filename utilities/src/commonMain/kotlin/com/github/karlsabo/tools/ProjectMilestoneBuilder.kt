package com.github.karlsabo.tools

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.projectmanagement.isMilestone
import com.github.karlsabo.tools.model.Milestone
import kotlin.time.Duration

private const val RECENT_MILESTONE_COMMENT_LIMIT = 5

internal suspend fun Project.buildProjectMilestones(
    parentIssues: List<ProjectIssue>,
    childIssues: List<ProjectIssue>,
    request: ProjectSummaryRequest,
    parentIssuesAreChildren: Boolean,
): Set<Milestone> = if (parentIssuesAreChildren) {
    emptySet()
} else {
    buildMilestones(
        parentIssues = parentIssues,
        childIssues = childIssues,
        users = request.users,
        projectManagementApi = request.dependencies.projectManagementApi,
        duration = request.duration,
    )
}

private suspend fun Project.buildMilestones(
    parentIssues: List<ProjectIssue>,
    childIssues: List<ProjectIssue>,
    users: Set<User>,
    projectManagementApi: ProjectManagementApi,
    duration: Duration,
): Set<Milestone> = parentIssues.plus(childIssues).toSet()
    .filter { it.isMilestone() }
    .map { milestoneIssue ->
        buildMilestone(milestoneIssue, users, projectManagementApi, duration)
    }.toSet()

private suspend fun Project.buildMilestone(
    milestoneIssue: ProjectIssue,
    users: Set<User>,
    projectManagementApi: ProjectManagementApi,
    duration: Duration,
): Milestone {
    val milestoneChildIssues = projectManagementApi.getDirectChildIssues(milestoneIssue.key)
        .filter { issue -> issue.isIssueOrBug() }
        .toSet()

    val owner = findMilestoneOwner(milestoneIssue, users)

    val milestoneCommentSet = mutableSetOf<ProjectComment>()
    if (milestoneIssue.key.isNotBlank()) {
        milestoneCommentSet.addAll(
            projectManagementApi.getRecentComments(milestoneIssue.key, RECENT_MILESTONE_COMMENT_LIMIT),
        )
    }

    return Milestone(
        owner,
        milestoneIssue,
        milestoneChildIssues,
        milestoneCommentSet,
        milestoneChildIssues.toList().filterRecentIssues(duration),
        mutableSetOf(),
    )
}

private fun Project.findMilestoneOwner(
    milestoneIssue: ProjectIssue,
    users: Set<User>,
): User? = if (milestoneIssue.assigneeName != null && milestoneIssue.assigneeName.isNotBlank()) {
    users.firstOrNull { it.name == milestoneIssue.assigneeName }
} else if (projectLeadUserId != null) {
    users.firstOrNull { it.email == projectLeadUserId }
} else {
    null
}
