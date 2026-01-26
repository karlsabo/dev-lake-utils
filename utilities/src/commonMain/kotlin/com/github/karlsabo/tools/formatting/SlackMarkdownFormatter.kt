package com.github.karlsabo.tools.formatting

import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.tools.model.ProjectSummary
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

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
fun ProjectSummary.toVerboseSlackMarkdown(): String {
    val summary = StringBuilder()
    createTitle(summary)

    summary.append(createSlackMarkdownProgressBar(issues, durationIssues))
    summary.append(this.durationProgressSummary)
    summary.appendLine()

    if (milestones.isNotEmpty()) {
        summary.appendLine("🛣️ *Milestones*")
        summary.appendLine()
        this.milestones.sortedBy { it.issue.title }.forEach { milestone ->
            if (milestone.issue.completedAt != null && milestone.issue.completedAt < Clock.System.now()
                    .minus(14.days)
            ) return@forEach

            summary.appendLine()
            val complete = if (milestone.issue.completedAt == null) "" else "✅ "
            summary.appendLine("*$complete<${milestone.issue.url}|${milestone.issue.title}>: ${milestone.issue.assigneeName ?: "No assignee"}*")
            summary.append(createSlackMarkdownProgressBar(milestone.issues, milestone.durationIssues))

            if (milestone.issue.completedAt == null) {
                val issuesResolved = milestone.durationIssues.filter { it.isCompleted() }
                if (issuesResolved.isNotEmpty()) {
                    summary.appendLine(
                        "📍 Issues resolved: ${
                            issuesResolved.joinToString(", ") { "<${it.url}|${it.key}>" }
                        }"
                    )
                } else {
                    val changeCharacterLimit = 200
                    val lastIssue = milestone.issues.sortedByDescending { it.completedAt }.firstOrNull()
                    val lastIssueResolutionDate = lastIssue?.completedAt

                    // Check for the most recent update from changelogs, issue resolutions, or comments
                    val lastComment = milestone.milestoneComments.maxByOrNull { it.createdAt ?: Clock.System.now() }
                    val lastCommentDate = lastComment?.createdAt

                    val isStatusRecent: Boolean
                    // Determine which is the most recent update: changelog, issue resolution, or comment
                    if (lastCommentDate != null &&
                        (lastIssueResolutionDate == null || lastCommentDate > lastIssue.completedAt)
                    ) {
                        // Comment is the most recent
                        val dateStr = lastCommentDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastCommentDate >= Clock.System.now().minus(14.days)
                        val warningEmoji = if (!isStatusRecent) "⚠️ " else ""
                        val commentBody = (lastComment.body ?: "").take(changeCharacterLimit)
                        val commentDescription =
                            commentBody + if ((lastComment.body?.length ?: 0) > changeCharacterLimit) "..." else ""
                        summary.appendLine("${warningEmoji}🗓️ Last update $dateStr: \"$commentDescription\"")
                    } else if (lastIssueResolutionDate != null) {
                        // Issue resolution is the most recent
                        val dateStr = lastIssueResolutionDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastIssueResolutionDate >= Clock.System.now().minus(14.days)
                        val warningEmoji =
                            if (!isStatusRecent) "⚠️ " else ""
                        summary.appendLine(
                            "$warningEmoji🗓️ Last update $dateStr: <${lastIssue.url}|${lastIssue.key}> \"${
                                lastIssue.title?.take(
                                    changeCharacterLimit
                                )
                            }${if ((lastIssue.title?.length ?: 0) > changeCharacterLimit) "..." else ""}\""
                        )
                    } else {
                        isStatusRecent = false
                    }

                    if (milestone.issue.dueDate == null) {
                        if (milestone.assignee == null) {
                            summary.appendLine("‼️⚠️ This milestone doesn't have a due date or an assignee.")
                        } else {
                            summary.append(milestone.assignee.name)
                            if (isTagMilestoneAssignees) summary.append(" <@${milestone.assignee.slackId}>")
                            summary.appendLine(", please add a due date on the Epic")
                        }
                    } else if (!isStatusRecent && milestone.issue.dueDate.minus(90.days) < Clock.System.now()) {
                        if (milestone.assignee == null) {
                            summary.appendLine("‼️⚠️ There hasn't been any activity for two weeks, and this Epic doesn't have an assignee")
                        } else {
                            summary.append(milestone.assignee.name)
                            if (isTagMilestoneAssignees) summary.append(" <@${milestone.assignee.slackId}>")
                            summary.appendLine(", there hasn't been any activity for two weeks, please add a status update comment on the Epic.")
                        }
                    }
                }
                val issuesOpened = milestone.durationIssues.filter { !it.isCompleted() }
                if (issuesOpened.isNotEmpty()) {
                    summary.appendLine(
                        "📩 Issues opened: ${
                            issuesOpened.joinToString(", ") { "<${it.url}|${it.key}>" }
                        }"
                    )
                }
                if (milestone.durationMergedPullRequests.isNotEmpty()) {
                    summary.appendLine("🔹 PRs merged: ${milestone.durationMergedPullRequests.joinToString(", ") { "<${it.htmlUrl}|${it.number}>" }}")
                }
            }
        }
    }
    return summary.toString()
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
            }"
        )
    }
    val issuesOpened = durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        summary.appendLine(
            "📩 Issues opened: ${
                issuesOpened.joinToString(", ") { "<${it.url}|${it.key}>" }
            }"
        )
    }
    if (durationMergedPullRequests.isNotEmpty()) {
        summary.appendLine("🔹 PRs merged: ${durationMergedPullRequests.joinToString(", ") { "<${it.htmlUrl}|${it.number}>" }}")
    }

    if (milestones.isNotEmpty()) {
        val milestoneSummary = StringBuilder()
        milestoneSummary.appendLine()

        milestoneSummary.appendLine("🛣️ *Milestones completed in the last 14 days*")
        milestoneSummary.appendLine()
        var milestoneCount = 0
        this.milestones.sortedBy { it.issue.title }.forEach { milestone ->
            if (milestone.issue.completedAt == null || milestone.issue.completedAt < Clock.System.now()
                    .minus(14.days)
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
