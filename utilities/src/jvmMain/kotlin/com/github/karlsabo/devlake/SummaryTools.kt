package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.*
import com.github.karlsabo.devlake.dto.DevLakeSummary
import com.github.karlsabo.devlake.dto.PagerDutyAlert
import com.github.karlsabo.devlake.dto.Project
import com.github.karlsabo.text.TextSummarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.Date
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class Milestone(
    val issue: Issue,
    val issues: Set<Issue>,
)

@Serializable
data class ProjectSummary(
    val project: Project,
    val durationProgressSummary: String,
    val issues: Set<Issue>,
    val durationIssues: Set<Issue>,
    val durationMergedPullRequests: Set<PullRequest>,
    val milestones: Set<Milestone>,
)

fun ProjectSummary.toSlackMarkdown(): String {
    val summary = StringBuilder()
    summary.appendLine()
    if (project.links != null && project.links.isNotEmpty()) {
        summary.appendLine("*<${project.links[0]}|${project.title}>*")
    } else {
        summary.appendLine("*${project.title}*")
    }

    if (milestones.isNotEmpty()) {
        val issueCount = issues.count { it.isIssueOrBug() }
        val closedIssueCount = issues.count { it.isIssueOrBug() && it.resolutionDate != null }
        val closedIssuePercentage = if (issueCount == 0) {
            0
        } else {
            (closedIssueCount / issueCount.toDouble() * 100).roundToInt()
        }
        repeat(closedIssuePercentage / 10) { summary.append("🟩") }
        repeat(10 - closedIssuePercentage / 10) { summary.append("⬜") }
        summary.append(" $closedIssuePercentage%")

        val netIssuesResolved =
            durationIssues.count { it.resolutionDate != null } - durationIssues.count { it.resolutionDate == null }
        if (netIssuesResolved == 0) {
            summary.append(" ⚖️ 0")
        } else if (netIssuesResolved > 0) {
            summary.append(" 📉 -${abs(netIssuesResolved)}")
        } else {
            summary.append(" 📈 +${abs(netIssuesResolved)}")
        }
        summary.appendLine(" net issues this week")

        // ignore story points for now
        if (false) {
            val totalStoryPoints = issues.sumOf { it.storyPoint ?: 0.0 }
            val completedStoryPoints = issues.filter { it.resolutionDate != null }.sumOf { it.storyPoint ?: 0.0 }
            val storyPointsPercentage = if (totalStoryPoints == 0.0) {
                0
            } else {
                (completedStoryPoints / totalStoryPoints * 100).roundToInt()
            }
            summary.append("[")
            repeat(storyPointsPercentage / 10) { summary.append("=") }
            repeat(10 - storyPointsPercentage / 10) { summary.append(" ") }
            summary.append("] $storyPointsPercentage% story points complete\n")
        }
    }

    summary.appendLine()
    summary.append(this.durationProgressSummary)
    summary.appendLine()
    summary.appendLine()
    val issuesResolved = durationIssues.filter { it.resolutionDate != null }
    if (issuesResolved.isNotEmpty()) {
        summary.appendLine(
            "📍 Issues resolved: ${
                issuesResolved.joinToString(", ") { "<${it.url}|${it.issueKey}>" }
            }"
        )
    }
    val issuesOpened = durationIssues.filter { it.resolutionDate == null }
    if (issuesOpened.isNotEmpty()) {
        summary.appendLine(
            "📩 Issues opened: ${
                issuesOpened.joinToString(", ") { "<${it.url}|${it.issueKey}>" }
            }"
        )
    }
    if (durationMergedPullRequests.isNotEmpty()) {
        summary.appendLine("🔹 PRs merged: ${durationMergedPullRequests.joinToString(", ") { "<${it.url}|${it.pullRequestKey}>" }}")
    }
    summary.appendLine()


    if (milestones.isNotEmpty()) {
        val milestoneSummary = StringBuilder()
        milestoneSummary.appendLine()

        milestoneSummary.appendLine("🛣️ *Milestones completed in the last 14 days*")
        milestoneSummary.appendLine()
        var milestoneCount = 0
        this.milestones.sortedBy { it.issue.title }.forEach { milestone ->
            if (milestone.issue.resolutionDate == null || milestone.issue.resolutionDate < Clock.System.now()
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

suspend fun createSummary(
    dataSource: DataSource,
    textSummarizer: TextSummarizer,
    projects: List<Project>,
    duration: Duration,
    users: List<User>,
    summaryName: String
): DevLakeSummary {
    val timeInPast = Clock.System.now().minus(duration)
    val timeInPastSql = Date(timeInPast.toEpochMilliseconds())

    val mutex = Mutex()
    val projectSummaries = mutableListOf<ProjectSummary>()
    var miscIssueSet = mutableSetOf<Issue>()

    coroutineScope {
        val projectJobs = projects.map { project ->
            async(Dispatchers.Default) {
                val projectSummary = project.createSummary(dataSource, textSummarizer, duration)
                mutex.withLock {
                    projectSummaries.add(projectSummary)
                }
            }
        }

        val issueAccessor = IssueAccessorDb(dataSource)
        val userAccountAccessor = UserAccountAccessorDb(dataSource)

        val userJobs = users.map {
            async(Dispatchers.Default) {
                val userAccounts = userAccountAccessor.getUserAccountByUserId(it.id)
                userAccounts.forEach {
                    val issuesForUser = issueAccessor.getIssuesByAssigneeIdAndAfterResolutionDate(
                        it.accountId,
                        Clock.System.now().minus(duration)
                    )
                    mutex.withLock {
                        miscIssueSet.addAll(
                            issuesForUser
                        )
                    }
                }
            }
        }

        projectJobs.joinAll()
        userJobs.joinAll()
    }
    projectSummaries.forEach { projectSummary ->
        miscIssueSet = miscIssueSet.subtract(projectSummary.issues).toMutableSet()
    }

    val pullRequestAccessor = PullRequestAccessorDb(dataSource)
    var miscPullRequests = mutableSetOf<PullRequest>()
    users.forEach {
        miscPullRequests.addAll(
            pullRequestAccessor
                .getPullRequestsByAuthorIdAndAfterMergedDate(it.id, Clock.System.now().minus(duration))
        )
    }
    projectSummaries.forEach { projectSummary ->
        miscPullRequests = miscPullRequests.subtract(projectSummary.durationMergedPullRequests).toMutableSet()
    }
    val miscProject = Project(
        id = 123456789101112L,
        title = "📋 Other (Misc)",
        topLevelIssueKeys = miscIssueSet.map { it.issueKey },
    )
    projectSummaries.add(miscProject.createSummary(dataSource, textSummarizer, duration, true))

    val pagerDutyAlertList = mutableListOf<PagerDutyAlert>()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
        SELECT
            *
        FROM
            issues
        WHERE
            issues.id LIKE '%page%'
            AND issues.resolution_date >= ?
        """.trimIndent()
        ).use { ps ->
            ps.setDate(1, timeInPastSql)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    pagerDutyAlertList.add(
                        PagerDutyAlert(
                            rs.getString("issue_key"),
                            rs.getString("description"),
                            rs.getString("url")
                        )
                    )
                }
            }
        }
    }

    return DevLakeSummary(
        timeInPast.toLocalDateTime(TimeZone.UTC).date,
        Clock.System.now().toLocalDateTime(TimeZone.UTC).date,
        summaryName,
        projectSummaries,
        pagerDutyAlertList,
    )
}

suspend fun Project.createSummary(
    source: DataSource,
    textSummarizer: TextSummarizer,
    duration: Duration,
    parentIssuesAreChildren: Boolean = false
): ProjectSummary {
    val issueAccessor = IssueAccessorDb(source)
    val parentIssues =
        if (topLevelIssueKeys.isEmpty()) emptyList<Issue>() else issueAccessor.getIssuesByKey(this.topLevelIssueKeys)
    val parentIssueIds = parentIssues.map { it.id }
    val childIssues = if (parentIssuesAreChildren) parentIssues else issueAccessor.getAllChildIssues(parentIssueIds)

    val resolvedChildIssues =
        childIssues.filter { it.resolutionDate != null && it.resolutionDate >= Clock.System.now().minus(duration) }

    val summary = if (resolvedChildIssues.isNotEmpty()) {
        val summaryRawInput = StringBuilder()
        summaryRawInput.appendLine("# Issues\n\n")
        resolvedChildIssues.forEach { issue ->
            summaryRawInput.appendLine("## ${issue.title}")
            summaryRawInput.appendLine("Assignee: ${issue.assigneeName}")
            summaryRawInput.appendLine("Description:\n````${issue.description}````\n")
        }
        textSummarizer.summarize(summaryRawInput.toString())
    } else {
        "* No updates in the last ${duration.inWholeDays} days*"
    }

    val mergedPrs = PullRequestAccessorDb(source).getPullRequestsMergedSinceWithIssueKey(
        childIssues.map { it.issueKey },
        duration
    ).toSet()

    val milestones = if (parentIssuesAreChildren) {
        emptySet<Milestone>()
    } else {
        parentIssues.plus(childIssues).toSet()
            .filter { it.isMilestone() }.map {
                Milestone(
                    it,
                    issueAccessor.getAllChildIssues(listOf(it.id)).filter { it.isIssueOrBug() }.toSet(),
                )
            }.toSet()
    }

    return ProjectSummary(
        this,
        summary,
        childIssues.filter { it.isIssueOrBug() }.toSet(),
        childIssues.filter {
            it.isIssueOrBug()
                    && (it.resolutionDate != null && it.resolutionDate >= Clock.System.now().minus(duration)
                    || it.createdDate != null && it.createdDate >= Clock.System.now().minus(duration))
        }
            .toSet(),
        mergedPrs,
        milestones,
    )
}
