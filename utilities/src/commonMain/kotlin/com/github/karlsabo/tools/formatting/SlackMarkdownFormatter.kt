package com.github.karlsabo.tools.formatting

import com.github.karlsabo.github.Issue
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.tools.model.Milestone
import com.github.karlsabo.tools.model.ProjectSummary
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days

private const val RECENT_ACTIVITY_DAYS = 14

/**
 * Converts the project summary into a detailed Slack-compatible Markdown string.
 *
 * The Markdown string includes prominently displayed project titles (wrapped with Slack-specific
 * formatting), progress bars, milestones with associated issues, and updates. The formatted string
 * provides a comprehensive overview of the project's status, milestones, issue resolutions, and
 * other changes.
 *
 * @return A formatted Markdown string that represents the project's verbose summary,
 *         tailored for Slack communication.
 */
fun ProjectSummary.toVerboseSlackMarkdown(): String = buildString {
    createTitle(this)
    append(createSlackMarkdownProgressBar(issues, durationIssues))
    append(durationProgressSummary)
    appendLine()
    appendVerboseMilestones(this@toVerboseSlackMarkdown)
}

private fun StringBuilder.appendVerboseMilestones(summary: ProjectSummary) {
    if (summary.milestones.isEmpty()) return

    appendLine("🛣️ *Milestones*")
    appendLine()
    summary.milestones
        .sortedBy { it.issue.title }
        .filterNot { it.wasCompletedBeforeRecentWindow() }
        .forEach { milestone -> appendVerboseMilestone(summary, milestone) }
}

private fun StringBuilder.appendVerboseMilestone(summary: ProjectSummary, milestone: Milestone) {
    appendLine()
    val complete = if (milestone.issue.completedAt == null) "" else "✅ "
    appendLine(milestone.header(complete))
    append(createSlackMarkdownProgressBar(milestone.issues, milestone.durationIssues))

    if (milestone.issue.completedAt == null) {
        appendOpenMilestoneDetails(summary, milestone)
    }
}

private fun StringBuilder.appendOpenMilestoneDetails(summary: ProjectSummary, milestone: Milestone) {
    val issuesResolved = milestone.durationIssues.filter { it.isCompleted() }
    if (issuesResolved.isNotEmpty()) {
        appendIssueLinks("📍 Issues resolved", issuesResolved)
    } else {
        appendMilestoneStatusWarning(summary, milestone)
    }

    val issuesOpened = milestone.durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        appendIssueLinks("📩 Issues opened", issuesOpened)
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
        appendLine("‼️⚠️ This milestone doesn't have a due date or an assignee.")
    } else {
        append(milestone.assignee.name)
        if (summary.isTagMilestoneAssignees) append(" <@${milestone.assignee.slackId}>")
        appendLine(", please add a due date on the Epic")
    }
}

private fun StringBuilder.appendStaleActivityWarning(summary: ProjectSummary, milestone: Milestone) {
    if (milestone.assignee == null) {
        appendLine("‼️⚠️ There hasn't been any activity for two weeks, and this Epic doesn't have an assignee")
    } else {
        append(milestone.assignee.name)
        if (summary.isTagMilestoneAssignees) append(" <@${milestone.assignee.slackId}>")
        appendLine(
            ", there hasn't been any activity for two weeks, " +
                "please add a status update comment on the Epic.",
        )
    }
}

private fun StringBuilder.appendIssueLinks(label: String, issues: List<ProjectIssue>) {
    appendLine("$label: ${issues.joinToString(", ") { "<${it.url}|${it.key}>" }}")
}

private fun StringBuilder.appendPullRequestLinks(pullRequests: Collection<Issue>) {
    appendLine("🔹 PRs merged: ${pullRequests.joinToString(", ") { "<${it.htmlUrl}|${it.number}>" }}")
}

internal fun ProjectSummary.createTitle(summary: StringBuilder) {
    if (!project.links.isNullOrEmpty()) {
        summary.appendLine("*<${project.links[0]}|${project.title}>*")
    } else {
        summary.appendLine("*${project.title}*")
    }
}

fun ProjectSummary.toTerseSlackMarkdown(): String {
    val summary = StringBuilder()
    summary.appendLine(project.title)
    summary.appendLine(createSlackMarkdownProgressBar(issues, durationIssues))
    return summary.toString()
}

fun ProjectSummary.toSlackMarkup(): String {
    val summary = StringBuilder()
    createTitle(summary)

    summary.append(createSlackMarkdownProgressBar(issues, durationIssues))

    summary.appendLine()
    summary.append(this.durationProgressSummary)
    summary.appendLine()
    summary.appendLine()
    val issuesResolved = durationIssues.filter { it.isCompleted() }
    if (issuesResolved.isNotEmpty()) {
        summary.appendLine(
            "📍 Issues resolved: ${
                issuesResolved.joinToString(", ") { "<${it.url}|${it.key}>" }
            }",
        )
    }
    val issuesOpened = durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        summary.appendLine(
            "📩 Issues opened: ${
                issuesOpened.joinToString(", ") { "<${it.url}|${it.key}>" }
            }",
        )
    }
    if (durationMergedPullRequests.isNotEmpty()) {
        summary.appendPullRequestLinks(durationMergedPullRequests)
    }

    if (milestones.isNotEmpty()) {
        val milestoneSummary = StringBuilder()
        milestoneSummary.appendLine()

        milestoneSummary.appendLine("🛣️ *Milestones completed in the last 14 days*")
        milestoneSummary.appendLine()
        var milestoneCount = 0
        this.milestones.sortedBy { it.issue.title }.forEach { milestone ->
            if (milestone.issue.completedAt == null || milestone.issue.completedAt < Clock.System.now()
                    .minus(RECENT_ACTIVITY_DAYS.days)
            ) {
                return@forEach
            }
            milestoneCount++
            milestoneSummary.appendLine("*✅ <${milestone.issue.url}|${milestone.issue.title}>*")
        }
        if (milestoneCount > 0) {
            summary.append(milestoneSummary.toString())
        }
    }
    return summary.toString()
}
