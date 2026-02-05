package com.github.karlsabo.linear.conversion

import com.github.karlsabo.common.datetime.DateTimeFormatting
import com.github.karlsabo.linear.Issue
import com.github.karlsabo.linear.WorkflowState
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.StatusCategory

/**
 * Converts a Linear Issue to a unified ProjectIssue.
 */
fun Issue.toProjectIssue(): ProjectIssue {
    return ProjectIssue(
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
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        dueDate = parseDueDate(dueDate),
    )
}

/**
 * Converts Linear WorkflowState to the unified StatusCategory.
 */
fun WorkflowState.toProjectStatusCategory(): StatusCategory? {
    return when (type?.lowercase()) {
        "backlog", "unstarted", "triage" -> StatusCategory.TODO
        "started" -> StatusCategory.IN_PROGRESS
        "completed" -> StatusCategory.DONE
        "canceled" -> StatusCategory.DONE // Treat canceled as done (no longer active)
        else -> null
    }
}

/**
 * Converts Linear priority integer to a string representation.
 */
internal fun priorityToString(priority: Int?): String? {
    return when (priority) {
        0 -> "No Priority"
        1 -> "Urgent"
        2 -> "High"
        3 -> "Medium"
        4 -> "Low"
        else -> null
    }
}

/**
 * Parses a Linear date string to an Instant.
 * Linear due dates are typically in "YYYY-MM-DD" format.
 */
internal fun parseDueDate(dueDate: String?) = dueDate?.let {
    runCatching { DateTimeFormatting.parseDateOnlyToInstant(it) }.getOrNull()
}
