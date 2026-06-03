package com.github.karlsabo.tools.formatting

import com.github.karlsabo.tools.model.Milestone

private const val RECENT_MILESTONES_HEADER = "🛣️ *Milestones completed in the last 14 days*"

internal fun StringBuilder.appendRecentCompletedMilestones(milestones: Set<Milestone>) {
    val milestoneLines = milestones
        .sortedBy { it.issue.title }
        .filter { it.wasCompletedWithinRecentWindow() }
        .map { it.completedMilestoneLine() }

    if (milestoneLines.isEmpty()) return

    appendLine()
    appendLine(RECENT_MILESTONES_HEADER)
    appendLine()
    milestoneLines.forEach { appendLine(it) }
}

private fun Milestone.wasCompletedWithinRecentWindow(): Boolean =
    issue.completedAt?.let { it >= recentActivityCutoff() } == true

private fun Milestone.completedMilestoneLine(): String = "*✅ <${issue.url}|${issue.title}>*"
