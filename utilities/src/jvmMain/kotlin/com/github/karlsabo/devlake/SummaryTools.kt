package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.Issue
import com.github.karlsabo.devlake.accessor.IssueAccessorDb
import com.github.karlsabo.devlake.accessor.IssueChangelog
import com.github.karlsabo.devlake.accessor.IssueChangelogAccessorDb
import com.github.karlsabo.devlake.accessor.PullRequest
import com.github.karlsabo.devlake.accessor.PullRequestAccessorDb
import com.github.karlsabo.devlake.accessor.User
import com.github.karlsabo.devlake.accessor.UserAccountAccessorDb
import com.github.karlsabo.devlake.accessor.isCompleted
import com.github.karlsabo.devlake.accessor.isIssueOrBug
import com.github.karlsabo.devlake.accessor.isMilestone
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
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class Milestone(
    val issue: Issue,
    val issues: Set<Issue>,
)

/**
 * Represents a summarized view of a project's details and related data.
 *
 * @property project The project associated with this summary.
 * @property durationProgressSummary A textual representation of the project's progress over time.
 * @property issues A set of issues related to the project.
 * @property issueChangeLogs A set of changelogs associated with the project's issues.
 * @property durationIssues A subset of issues filtered by duration.
 * @property durationMergedPullRequests A set of pull requests that were merged within a specific duration.
 * @property milestones A set of milestones relevant to the project.
 */
@Serializable
data class ProjectSummary(
    val project: Project,
    val durationProgressSummary: String,
    val issues: Set<Issue>,
    val issueChangeLogs: List<IssueChangelog>,
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

    val issueCount = issues.count { it.isIssueOrBug() }
    val closedIssueCount = issues.count { it.isIssueOrBug() && it.isCompleted() }
    val closedIssuePercentage = if (issueCount == 0) {
        0
    } else {
        (closedIssueCount / issueCount.toDouble() * 100).roundToInt()
    }

    val closedIssueCountThisWeek = durationIssues.count { it.isIssueOrBug() && it.isCompleted() }
    val closedIssuePercentageThisWeek = if (durationIssues.isEmpty()) {
        0
    } else {
        (closedIssueCountThisWeek / issueCount.toDouble() * 100).roundToInt()
    }
    val barCountThisWeek = ceil(closedIssuePercentageThisWeek / 10.0).roundToInt()

    println("For project ${project.title}, $closedIssueCount of $issueCount issues are closed, or $closedIssuePercentage%")
    issues.filter { it.isIssueOrBug() && !it.isCompleted() }.forEach {
        println("\t${it.issueKey} ${it.title} ${it.resolutionDate}, status=${it.status}, completed=${it.isCompleted()}")
    }

    val totalBarCount = 10
    val closedIssueBarCount = closedIssuePercentage / totalBarCount
    repeat(closedIssueBarCount - barCountThisWeek) { summary.append("🟦") }
    repeat(barCountThisWeek) { summary.append("🟨") }
    repeat(totalBarCount - closedIssueBarCount) { summary.append("⬜") }
    summary.append(" $closedIssuePercentage%")

    val netIssuesResolved =
        durationIssues.count { it.isCompleted() } - durationIssues.count { !it.isCompleted() }
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
        val completedStoryPoints = issues.filter { it.isCompleted() }.sumOf { it.storyPoint ?: 0.0 }
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

    summary.appendLine()
    summary.append(this.durationProgressSummary)
    summary.appendLine()
    summary.appendLine()
    val issuesResolved = durationIssues.filter { it.isCompleted() }
    if (issuesResolved.isNotEmpty()) {
        summary.appendLine(
            "📍 Issues resolved: ${
                issuesResolved.joinToString(", ") { "<${it.url}|${it.issueKey}>" }
            }"
        )
    }
    val issuesOpened = durationIssues.filter { !it.isCompleted() }
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
    projectSummaries.sortBy { it.project.title?.replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "") }
    projectSummaries.forEach { projectSummary ->
        miscPullRequests = miscPullRequests.subtract(projectSummary.durationMergedPullRequests).toMutableSet()
    }
    val miscProject = Project(
        id = 123456789101112L,
        title = "📋 Other (Misc)",
        topLevelIssueIds = miscIssueSet.map { it.id },
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
            AND issues.created_date >= ?
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
    val parentIssues = if (topLevelIssueKeys.isEmpty())
        mutableListOf<Issue>()
    else
        issueAccessor.getIssuesByKey(this.topLevelIssueKeys)
            .toMutableList()
    if (topLevelIssueIds.isNotEmpty())
        parentIssues += issueAccessor.getIssuesById(topLevelIssueIds)
    val parentIssueIds = parentIssues.map { it.id } + topLevelIssueIds
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

    val issueChangelogAccessor = IssueChangelogAccessorDb(source)
    val issueIds = (parentIssues + childIssues).map { it.id }.toSet()
    val issueChangelogs =
        if (issueIds.isEmpty())
            emptyList()
        else
            issueChangelogAccessor.getPaginatedChangelogsByIssueIds(issueIds, 10, 0)

    return ProjectSummary(
        this,
        summary,
        childIssues.filter { it.isIssueOrBug() }.toSet(),
        issueChangelogs,
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
