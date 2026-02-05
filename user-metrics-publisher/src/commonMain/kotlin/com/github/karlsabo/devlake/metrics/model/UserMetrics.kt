package com.github.karlsabo.devlake.metrics.model

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import com.github.karlsabo.github.Issue as GitHubIssue

@Serializable
data class UserMetrics(
    val userId: String,
    val pullRequestsPastWeek: List<GitHubIssue>,
    val pullRequestsYearToDateCount: UInt,
    val prReviewCountYtd: UInt,
    val issuesClosedLastWeek: List<ProjectIssue>,
    val issuesClosedYearToDateCount: UInt,
)

fun UserMetrics.toSlackMarkdown(): String {
    val weeksThisYear = weeksElapsedThisYear()

    return buildString {
        appendLine("📌 *Merged PRs*")
        appendLine("• *Past week:* `${pullRequestsPastWeek.size}`")
        appendLine("• *Year to Date:* `$pullRequestsYearToDateCount`. _Expectation ~${(weeksThisYear * 3.5).toInt()} (3-4 per week)_")
        appendLine("• *PRs Reviewed YTD:* `$prReviewCountYtd`")
        appendLine()
        appendLine("📌 *Issues Closed*")
        appendLine("• *Past week:* `${issuesClosedLastWeek.size}`")
        appendLine("• *Year to date:* `$issuesClosedYearToDateCount`. _Expectation ~${(weeksThisYear * 3.5).toInt()} (3-4 per week)_")
        appendLine("\n━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("🔍 *Details:*")
        appendLine("🔹 *Merged PRs:*")
        pullRequestsPastWeek.forEach {
            appendLine("• <${it.htmlUrl}|${it.number}> ${it.title}")
        }
        appendLine()
        appendLine("🔹 *Issues Closed:*")
        issuesClosedLastWeek.forEach {
            appendLine("• <${it.url}|${it.key}> ${it.title}")
        }
    }
}

private fun weeksElapsedThisYear(): Int {
    val now = System.now()
    val startOfYear = now.toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("${year}-01-01T00:00:00Z") }
    return ((now - startOfYear).inWholeDays / 7).toInt()
}
