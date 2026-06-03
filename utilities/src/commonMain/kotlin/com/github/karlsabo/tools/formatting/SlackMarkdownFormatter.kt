package com.github.karlsabo.tools.formatting

import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.tools.model.ProjectSummary

/** Converts a project summary into Slack-compatible Markdown. */
fun ProjectSummary.toVerboseSlackMarkdown(): String = buildString {
    createTitle(this)
    append(createSlackMarkdownProgressBar(issues, durationIssues))
    append(durationProgressSummary)
    appendLine()
    appendVerboseMilestones(this@toVerboseSlackMarkdown)
}

fun ProjectSummary.toTerseSlackMarkdown(): String = buildString {
    appendLine(project.title)
    appendLine(createSlackMarkdownProgressBar(issues, durationIssues))
}

fun ProjectSummary.toSlackMarkup(): String = buildString {
    createTitle(this)
    append(createSlackMarkdownProgressBar(issues, durationIssues))
    appendLine()
    append(durationProgressSummary)
    appendLine()
    appendLine()
    appendDurationIssueChanges(this@toSlackMarkup)

    if (durationMergedPullRequests.isNotEmpty()) {
        appendPullRequestLinks(durationMergedPullRequests)
    }

    appendRecentCompletedMilestones(milestones)
}

private fun StringBuilder.appendDurationIssueChanges(summary: ProjectSummary) {
    val issuesResolved = summary.durationIssues.filter { it.isCompleted() }
    if (issuesResolved.isNotEmpty()) {
        appendResolvedIssueLinks(issuesResolved)
    }

    val issuesOpened = summary.durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        appendOpenedIssueLinks(issuesOpened)
    }
}
