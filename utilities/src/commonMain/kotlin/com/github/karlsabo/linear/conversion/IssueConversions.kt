package com.github.karlsabo.linear.conversion

import com.github.karlsabo.common.datetime.DateTimeFormatting
import com.github.karlsabo.linear.Issue
import com.github.karlsabo.linear.WorkflowState
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.StatusCategory

private const val NO_PRIORITY = 0
private const val URGENT_PRIORITY = 1
private const val HIGH_PRIORITY = 2
private const val MEDIUM_PRIORITY = 3
private const val LOW_PRIORITY = 4

/**
 * Converts a Linear Issue to a unified ProjectIssue.
 */
fun Issue.toProjectIssue(): ProjectIssue = ProjectIssue(
    id = id,
    key = identifier ?: id,
    url = url,
    title = title,
    description = description,
    status = state?.name,
    statusCategory = state?.toProjectStatusCategory(),
    issueType = "Issue", // Linear doesn't have issue types like Jira
    priority = priorityToString(priority),
    estimate = estimate,
    assigneeId = assignee?.id,
    assigneeName = assignee?.displayName ?: assignee?.name,
    creatorId = creator?.id,
    creatorName = creator?.displayName ?: creator?.name,
    parentKey = parent?.identifier,
    projectId = project?.id,
    projectName = project?.name,
    milestoneId = projectMilestone?.id,
    milestoneName = projectMilestone?.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    dueDate = parseDueDate(dueDate),
)

/**
 * Converts Linear WorkflowState to the unified StatusCategory.
 */
fun WorkflowState.toProjectStatusCategory(): StatusCategory? = when (type?.lowercase()) {
    "backlog", "unstarted", "triage" -> StatusCategory.TODO

    "started" -> StatusCategory.IN_PROGRESS

    "completed" -> StatusCategory.DONE

    "canceled" -> StatusCategory.DONE

    // Treat canceled as done (no longer active)
    else -> null
}

/**
 * Converts Linear priority integer to a string representation.
 */
internal fun priorityToString(priority: Int?): String? = when (priority) {
    NO_PRIORITY -> "No Priority"
    URGENT_PRIORITY -> "Urgent"
    HIGH_PRIORITY -> "High"
    MEDIUM_PRIORITY -> "Medium"
    LOW_PRIORITY -> "Low"
    else -> null
}

/**
 * Parses a Linear date string to an Instant.
 * Linear due dates are typically in "YYYY-MM-DD" format.
 */
internal fun parseDueDate(dueDate: String?) = dueDate?.let {
    runCatching { DateTimeFormatting.parseDateOnlyToInstant(it) }.getOrNull()
}
