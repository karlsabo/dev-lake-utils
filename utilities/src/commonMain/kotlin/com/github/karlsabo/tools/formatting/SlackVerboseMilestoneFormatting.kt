package com.github.karlsabo.tools.formatting

import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.tools.model.Milestone
import com.github.karlsabo.tools.model.ProjectSummary

private const val MILESTONES_HEADER = "🛣️ *Milestones*"
private const val COMPLETE_PREFIX = "✅ "
private const val MISSING_DUE_DATE_AND_ASSIGNEE = "‼️⚠️ This milestone doesn't have a due date or an assignee."
private const val ADD_DUE_DATE_REQUEST = ", please add a due date on the Epic"
private const val STALE_ACTIVITY_WARNING = "There hasn't been any activity for two weeks"
private const val STALE_ACTIVITY_REQUEST = "there hasn't been any activity for two weeks"
private const val UNASSIGNED_EPIC_SUFFIX = "and this Epic doesn't have an assignee"
private const val STATUS_UPDATE_REQUEST = "please add a status update comment on the Epic."

internal fun StringBuilder.appendVerboseMilestones(summary: ProjectSummary) {
    if (summary.milestones.isEmpty()) return

    appendLine(MILESTONES_HEADER)
    appendLine()
    summary.milestones
        .sortedBy { it.issue.title }
        .filterNot { it.wasCompletedBeforeRecentWindow() }
        .forEach { milestone -> appendVerboseMilestone(summary, milestone) }
}

private fun StringBuilder.appendVerboseMilestone(summary: ProjectSummary, milestone: Milestone) {
    appendLine()
    val complete = if (milestone.issue.completedAt == null) "" else COMPLETE_PREFIX
    appendLine(milestone.header(complete))
    append(createSlackMarkdownProgressBar(milestone.issues, milestone.durationIssues))

    if (milestone.issue.completedAt == null) {
        appendOpenMilestoneDetails(summary, milestone)
    }
}

private fun StringBuilder.appendOpenMilestoneDetails(summary: ProjectSummary, milestone: Milestone) {
    val issuesResolved = milestone.durationIssues.filter { it.isCompleted() }
    if (issuesResolved.isNotEmpty()) {
        appendResolvedIssueLinks(issuesResolved)
    } else {
        appendMilestoneStatusWarning(summary, milestone)
    }

    val issuesOpened = milestone.durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        appendOpenedIssueLinks(issuesOpened)
    }
    if (milestone.durationMergedPullRequests.isNotEmpty()) {
        appendPullRequestLinks(milestone.durationMergedPullRequests)
    }
}

private fun StringBuilder.appendMilestoneStatusWarning(summary: ProjectSummary, milestone: Milestone) {
    val status = milestone.latestStatus()
    if (status != null) {
        appendLine(status.format())
    }

    when {
        milestone.issue.dueDate == null -> appendMissingDueDateWarning(summary, milestone)
        status?.isRecent == false && milestone.issue.dueDate.isWithinStaleWarningWindow() -> {
            appendStaleActivityWarning(summary, milestone)
        }
    }
}

private fun StringBuilder.appendMissingDueDateWarning(summary: ProjectSummary, milestone: Milestone) {
    if (milestone.assignee == null) {
        appendLine(MISSING_DUE_DATE_AND_ASSIGNEE)
    } else {
        appendAssigneeRequest(summary, milestone, ADD_DUE_DATE_REQUEST)
    }
}

private fun StringBuilder.appendStaleActivityWarning(summary: ProjectSummary, milestone: Milestone) {
    if (milestone.assignee == null) {
        appendLine("‼️⚠️ $STALE_ACTIVITY_WARNING, $UNASSIGNED_EPIC_SUFFIX")
    } else {
        appendAssigneeRequest(
            summary,
            milestone,
            ", $STALE_ACTIVITY_REQUEST, $STATUS_UPDATE_REQUEST",
        )
    }
}

private fun StringBuilder.appendAssigneeRequest(
    summary: ProjectSummary,
    milestone: Milestone,
    request: String,
) {
    val assignee = requireNotNull(milestone.assignee)
    append(assignee.name)
    if (summary.isTagMilestoneAssignees) append(" <@${assignee.slackId}>")
    appendLine(request)
}
