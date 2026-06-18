package com.github.karlsabo.devlake.metrics.model

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock.System
import kotlin.time.Instant
import com.github.karlsabo.github.Issue as GitHubIssue

private const val DAYS_PER_WEEK = 7
private const val EXPECTED_WEEKLY_CONTRIBUTIONS = 3.5
private const val EXPECTED_WEEKLY_CONTRIBUTIONS_LABEL = "3-4 per week"

@Serializable
data class UserMetrics(
    val userId: String,
    val email: String,
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
        appendLine(
            yearToDateExpectationLine(
                label = "Year to Date",
                count = pullRequestsYearToDateCount,
                weeksThisYear = weeksThisYear,
            ),
        )
        appendLine("• *PRs Reviewed YTD:* `$prReviewCountYtd`")
        appendLine()
        appendLine("📌 *Issues Closed*")
        appendLine("• *Past week:* `${issuesClosedLastWeek.size}`")
        appendLine(
            yearToDateExpectationLine(
                label = "Year to date",
                count = issuesClosedYearToDateCount,
                weeksThisYear = weeksThisYear,
            ),
        )
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

private fun yearToDateExpectationLine(
    label: String,
    count: UInt,
    weeksThisYear: Int,
): String {
    val expectedCount = (weeksThisYear * EXPECTED_WEEKLY_CONTRIBUTIONS).toInt()
    return "• *$label:* `$count`. _Expectation ~$expectedCount ($EXPECTED_WEEKLY_CONTRIBUTIONS_LABEL)_"
}

private fun weeksElapsedThisYear(): Int {
    val now = System.now()
    val startOfYear = now.toLocalDateTime(TimeZone.currentSystemDefault())
        .run { Instant.parse("$year-01-01T00:00:00Z") }
    return ((now - startOfYear).inWholeDays / DAYS_PER_WEEK).toInt()
}
