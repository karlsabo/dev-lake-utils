package com.github.karlsabo.tools.formatting

import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.tools.model.Milestone
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

private const val CHANGE_CHARACTER_LIMIT = 200
private const val STALE_DUE_DATE_WARNING_DAYS = 90
private const val EASTERN_TIME_ZONE = "America/New_York"
private const val TRUNCATION_INDICATOR = "..."

internal fun Milestone.header(complete: String): String {
    val assignee = issue.assigneeName ?: "No assignee"
    return "*$complete<${issue.url}|${issue.title}>: $assignee*"
}

internal fun Milestone.wasCompletedBeforeRecentWindow(): Boolean {
    val completedAt = issue.completedAt
    return completedAt?.let { it < recentActivityCutoff() } == true
}

internal fun Instant?.isWithinStaleWarningWindow(): Boolean {
    val dueDate = this
    return dueDate != null && dueDate.minus(STALE_DUE_DATE_WARNING_DAYS.days) < Clock.System.now()
}

internal fun Milestone.latestStatus(): MilestoneStatus? {
    val lastIssue = issues.sortedByDescending { it.completedAt }.firstOrNull()
    val lastIssueResolutionDate = lastIssue?.completedAt
    val lastComment = milestoneComments.maxByOrNull { it.createdAt ?: Clock.System.now() }
    val lastCommentDate = lastComment?.createdAt

    return if (lastCommentDate != null && lastCommentDate.isAfter(lastIssueResolutionDate)) {
        MilestoneStatus.Comment(lastCommentDate, lastComment.body.orEmpty())
    } else if (lastIssueResolutionDate != null) {
        MilestoneStatus.IssueResolution(lastIssueResolutionDate, lastIssue)
    } else {
        null
    }
}

private fun Instant.isAfter(other: Instant?): Boolean = other == null || this > other

internal sealed interface MilestoneStatus {
    val date: Instant
    val isRecent: Boolean
        get() = date >= recentActivityCutoff()

    fun format(): String

    data class Comment(
        override val date: Instant,
        val body: String,
    ) : MilestoneStatus {
        override fun format(): String {
            val dateStr = date.toEasternDate()
            val warningEmoji = if (!isRecent) "⚠️ " else ""
            return "$warningEmoji🗓️ Last update $dateStr: \"${body.truncateForSlack()}\""
        }
    }

    data class IssueResolution(
        override val date: Instant,
        val issue: ProjectIssue,
    ) : MilestoneStatus {
        override fun format(): String {
            val dateStr = date.toEasternDate()
            val warningEmoji = if (!isRecent) "⚠️ " else ""
            return "$warningEmoji🗓️ Last update $dateStr: <${issue.url}|${issue.key}> " +
                "\"${issue.title.orEmpty().truncateForSlack()}\""
        }
    }
}

private fun Instant.toEasternDate() = toLocalDateTime(TimeZone.of(EASTERN_TIME_ZONE)).date

private fun String.truncateForSlack(): String {
    val suffix = if (length > CHANGE_CHARACTER_LIMIT) TRUNCATION_INDICATOR else ""
    return take(CHANGE_CHARACTER_LIMIT) + suffix
}
