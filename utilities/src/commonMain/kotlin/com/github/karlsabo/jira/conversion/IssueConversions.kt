package com.github.karlsabo.jira.conversion

import com.github.karlsabo.jira.extensions.toPlainText
import com.github.karlsabo.jira.model.Issue
import com.github.karlsabo.jira.model.StatusCategory
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectMilestone
import com.github.karlsabo.projectmanagement.StatusCategory as UnifiedStatusCategory

/**
 * Converts a Jira Issue to a unified ProjectIssue.
 */
fun Issue.toProjectIssue(): ProjectIssue {
    return ProjectIssue(
        id = id,
        key = key,
        url = htmlUrl,
        title = fields.summary,
        description = fields.description.toPlainText(),
        status = fields.status?.name,
        statusCategory = fields.status?.statusCategory?.toProjectStatusCategory(),
        issueType = fields.issueType?.name,
        priority = fields.priority?.name,
        estimate = fields.customfield_10100, // Story points
        assigneeId = fields.assignee?.accountId,
        assigneeName = fields.assignee?.displayName,
        creatorId = fields.creator?.accountId,
        creatorName = fields.creator?.displayName,
        parentKey = fields.parent?.key,
        createdAt = fields.created,
        updatedAt = fields.updated,
        completedAt = fields.resolutionDate,
        dueDate = fields.dueDate,
    )
}

/**
 * Converts a Jira Issue (Epic) to a ProjectMilestone.
 */
fun Issue.toProjectMilestone(): ProjectMilestone {
    return ProjectMilestone(
        id = key,
        name = fields.summary,
        description = fields.description.toPlainText(),
        targetDate = fields.dueDate,
        progress = null, // Jira doesn't have native progress on Epics
        status = fields.status?.name,
        projectId = fields.project?.key,
    )
}

/**
 * Converts a Jira StatusCategory to the unified StatusCategory.
 */
fun StatusCategory.toProjectStatusCategory(): UnifiedStatusCategory? {
    return when (this.key?.lowercase()) {
        "new", "undefined" -> UnifiedStatusCategory.TODO
        "indeterminate" -> UnifiedStatusCategory.IN_PROGRESS
        "done" -> UnifiedStatusCategory.DONE
        else -> null
    }
}
