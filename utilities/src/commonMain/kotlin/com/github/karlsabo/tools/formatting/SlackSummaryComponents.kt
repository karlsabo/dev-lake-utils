package com.github.karlsabo.tools.formatting

import com.github.karlsabo.github.Issue
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.tools.model.ProjectSummary

private const val RESOLVED_ISSUES_LABEL = "📍 Issues resolved"
private const val OPENED_ISSUES_LABEL = "📩 Issues opened"
private const val MERGED_PULL_REQUESTS_LABEL = "🔹 PRs merged"

internal fun ProjectSummary.createTitle(summary: StringBuilder) {
    if (!project.links.isNullOrEmpty()) {
        summary.appendLine("*<${project.links[0]}|${project.title}>*")
    } else {
        summary.appendLine("*${project.title}*")
    }
}

internal fun StringBuilder.appendResolvedIssueLinks(issues: List<ProjectIssue>) {
    appendIssueLinks(RESOLVED_ISSUES_LABEL, issues)
}

internal fun StringBuilder.appendOpenedIssueLinks(issues: List<ProjectIssue>) {
    appendIssueLinks(OPENED_ISSUES_LABEL, issues)
}

private fun StringBuilder.appendIssueLinks(label: String, issues: List<ProjectIssue>) {
    appendLine("$label: ${issues.joinToString(", ") { it.toSlackLink() }}")
}

internal fun StringBuilder.appendPullRequestLinks(pullRequests: Collection<Issue>) {
    appendLine("$MERGED_PULL_REQUESTS_LABEL: ${pullRequests.joinToString(", ") { it.toSlackLink() }}")
}

private fun ProjectIssue.toSlackLink(): String = "<$url|$key>"

private fun Issue.toSlackLink(): String = "<$htmlUrl|$number>"
