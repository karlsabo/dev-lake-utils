package com.github.karlsabo.tools.formatting

import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.isCompleted
import com.github.karlsabo.projectmanagement.isIssueOrBug
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val TOTAL_BAR_COUNT = 10
private const val PERCENT_PER_BAR = 10.0

fun createSlackMarkdownProgressBar(issues: Set<ProjectIssue>, durationIssues: Set<ProjectIssue>): String {
    val progressBar = StringBuilder()
    val issueCount = issues.count { it.isIssueOrBug() }
    val closedIssueCount = issues.count { it.isIssueOrBug() && it.isCompleted() }
    val closedIssuePercentage = if (issueCount == 0) {
        0
    } else {
        (closedIssueCount / issueCount.toDouble() * PERCENT_SCALE).roundToInt()
    }

    val closedIssueCountThisWeek = durationIssues.count { it.isIssueOrBug() && it.isCompleted() }
    val closedIssuePercentageThisWeek = if (durationIssues.isEmpty()) {
        0
    } else {
        (closedIssueCountThisWeek / issueCount.toDouble() * PERCENT_SCALE).roundToInt()
    }
    val barCountThisWeek = ceil(closedIssuePercentageThisWeek / PERCENT_PER_BAR).roundToInt()

    val closedIssueBarCount = closedIssuePercentage / TOTAL_BAR_COUNT
    repeat(closedIssueBarCount - barCountThisWeek) { progressBar.append("🟦") }
    repeat(barCountThisWeek) { progressBar.append("🟨") }
    repeat(TOTAL_BAR_COUNT - closedIssueBarCount) { progressBar.append("⬜") }
    progressBar.append(" $closedIssuePercentage%")

    val netIssuesResolved =
        durationIssues.count { it.isCompleted() } - durationIssues.count { !it.isCompleted() }
    if (netIssuesResolved == 0) {
        progressBar.append(" ⚖️ 0")
    } else if (netIssuesResolved > 0) {
        progressBar.append(" 📉 -${abs(netIssuesResolved)}")
    } else {
        progressBar.append(" 📈 +${abs(netIssuesResolved)}")
    }
    progressBar.appendLine(" net issues this week")
    return progressBar.toString()
}
