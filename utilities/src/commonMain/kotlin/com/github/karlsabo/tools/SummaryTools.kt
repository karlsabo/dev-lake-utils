package com.github.karlsabo.tools

import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.jira.Comment
import com.github.karlsabo.jira.Issue
import com.github.karlsabo.jira.JiraApi
import com.github.karlsabo.jira.isCompleted
import com.github.karlsabo.jira.isIssueOrBug
import com.github.karlsabo.jira.isMilestone
import com.github.karlsabo.jira.toPlainText
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyIncident
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class Milestone(
    val assignee: User?,
    val issue: Issue,
    val issues: Set<Issue>,
    val milestoneComments: Set<Comment>,
    val durationIssues: Set<Issue>,
    val durationMergedPullRequests: Set<com.github.karlsabo.github.Issue>,
)

/**
 * Represents a summarized view of a project's details and related data.
 *
 * @property project The project associated with this summary.
 * @property durationProgressSummary A textual representation of the project's progress over time.
 * @property issues A set of issues related to the project.
 * @property durationIssues A subset of issues filtered by duration.
 * @property durationMergedPullRequests A set of pull requests that were merged within a specific duration.
 * @property milestones A set of milestones relevant to the project.
 */
@Serializable
data class ProjectSummary(
    val project: Project,
    val durationProgressSummary: String,
    val issues: Set<Issue>,
    val durationIssues: Set<Issue>,
    val durationMergedPullRequests: Set<com.github.karlsabo.github.Issue>,
    val milestones: Set<Milestone>,
    val isTagMilestoneAssignees: Boolean,
)

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
        this.milestones.sortedBy { it.issue.fields.summary }.forEach { milestone ->
            if (milestone.issue.fields.resolutionDate != null && milestone.issue.fields.resolutionDate < Clock.System.now()
                    .minus(14.days)
            ) return@forEach

            summary.appendLine()
            val complete = if (milestone.issue.fields.resolutionDate == null) "" else "✅ "
            summary.appendLine("*$complete<${milestone.issue.htmlUrl}|${milestone.issue.fields.summary}>: ${milestone.issue.fields.assignee?.displayName ?: "No assignee"}*")
            summary.append(createSlackMarkdownProgressBar(milestone.issues, milestone.durationIssues))

            if (milestone.issue.fields.resolutionDate == null) {
                val issuesResolved = milestone.durationIssues.filter { it.isCompleted() }
                if (issuesResolved.isNotEmpty()) {
                    summary.appendLine(
                        "📍 Issues resolved: ${
                            issuesResolved.joinToString(", ") { "<${it.htmlUrl}|${it.key}>" }
                        }"
                    )
                } else {
                    val changeCharacterLimit = 200
                    val lastIssue = milestone.issues.sortedByDescending { it.fields.resolutionDate }.firstOrNull()
                    val lastIssueResolutionDate = lastIssue?.fields?.resolutionDate

                    // Check for the most recent update from changelogs, issue resolutions, or comments
                    val lastComment = milestone.milestoneComments.maxByOrNull { it.created }
                    val lastCommentDate = lastComment?.created

                    val isStatusRecent: Boolean
                    // Determine which is the most recent update: changelog, issue resolution, or comment
                    if (lastCommentDate != null &&
                        (lastIssueResolutionDate == null || lastCommentDate > lastIssue.fields.resolutionDate)
                    ) {
                        // Comment is the most recent
                        val dateStr = lastCommentDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastCommentDate >= Clock.System.now().minus(14.days)
                        val warningEmoji = if (!isStatusRecent) "⚠️ " else ""
                        val commentBody = lastComment.body.toPlainText().take(changeCharacterLimit)
                        val commentDescription =
                            commentBody + if (lastComment.body.toPlainText().length > changeCharacterLimit) "..." else ""
                        summary.appendLine("${warningEmoji}🗓️ Last update $dateStr: \"$commentDescription\"")
                    } else if (lastIssueResolutionDate != null) {
                        // Issue resolution is the most recent
                        val dateStr = lastIssueResolutionDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastIssueResolutionDate >= Clock.System.now().minus(14.days)
                        val warningEmoji =
                            if (!isStatusRecent) "⚠️ " else ""
                        summary.appendLine(
                            "$warningEmoji🗓️ Last update $dateStr: <${lastIssue.htmlUrl}|${lastIssue.key}> \"${
                                lastIssue.fields.summary?.take(
                                    changeCharacterLimit
                                )
                            }${if ((lastIssue.fields.summary?.length ?: 0) > changeCharacterLimit) "..." else ""}\""
                        )
                    } else {
                        isStatusRecent = false
                    }

                    if (milestone.issue.fields.dueDate == null) {
                        if (milestone.assignee == null) {
                            summary.appendLine("‼️⚠️ This milestone doesn't have a due date or an assignee.")
                        } else {
                            summary.append(milestone.assignee.name)
                            if (isTagMilestoneAssignees) summary.append(" <@${milestone.assignee.slackId}>")
                            summary.appendLine(", please add a due date on the Epic")
                        }
                    } else if (!isStatusRecent && milestone.issue.fields.dueDate.minus(90.days) < Clock.System.now()) {
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
                            issuesOpened.joinToString(", ") { "<${it.htmlUrl}|${it.key}>" }
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

private fun ProjectSummary.createTitle(summary: StringBuilder) {
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
                issuesResolved.joinToString(", ") { "<${it.htmlUrl}|${it.key}>" }
            }"
        )
    }
    val issuesOpened = durationIssues.filter { !it.isCompleted() }
    if (issuesOpened.isNotEmpty()) {
        summary.appendLine(
            "📩 Issues opened: ${
                issuesOpened.joinToString(", ") { "<${it.htmlUrl}|${it.key}>" }
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
        this.milestones.sortedBy { it.issue.fields.summary }.forEach { milestone ->
            if (milestone.issue.fields.resolutionDate == null || milestone.issue.fields.resolutionDate < Clock.System.now()
                    .minus(14.days)
            ) {
                return@forEach
            }
            milestoneCount++
            milestoneSummary.appendLine("*✅ <${milestone.issue.htmlUrl}|${milestone.issue.fields.summary}>*")
        }
        if (milestoneCount > 0) {
            summary.append(milestoneSummary.toString())
        }
    }
    return summary.toString()
}

private fun createSlackMarkdownProgressBar(issues: Set<Issue>, durationIssues: Set<Issue>): String {
    val progressBar = StringBuilder()
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

    val totalBarCount = 10
    val closedIssueBarCount = closedIssuePercentage / totalBarCount
    repeat(closedIssueBarCount - barCountThisWeek) { progressBar.append("🟦") }
    repeat(barCountThisWeek) { progressBar.append("🟨") }
    repeat(totalBarCount - closedIssueBarCount) { progressBar.append("⬜") }
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

suspend fun createSummary(
    jiraApi: JiraApi,
    gitHubApi: GitHubApi,
    gitHubOrganizationIds: List<String>,
    pagerDutyApi: PagerDutyApi?,
    pagerDutyServiceIds: List<String>,
    textSummarizer: TextSummarizer,
    projects: List<Project>,
    duration: Duration,
    users: List<User>,
    miscUsers: List<User>,
    summaryName: String,
    isMiscellaneousProjectIncluded: Boolean,
): MultiProjectSummary {
    val timeInPast = Clock.System.now().minus(duration)

    val mutex = Mutex()
    val projectSummaries = mutableListOf<ProjectSummary>()
    val miscIssueSet = mutableSetOf<Issue>()
    val miscPrSet = mutableSetOf<com.github.karlsabo.github.Issue>()

    coroutineScope {
        val projectJobs = projects.map { project ->
            async(Dispatchers.Default) {
                println("Creating summary for project ${project.title}")
                val projectSummary =
                    project.createSummary(
                        users.toSet(),
                        gitHubApi,
                        gitHubOrganizationIds,
                        jiraApi,
                        textSummarizer,
                        duration,
                        emptySet()
                    )
                mutex.withLock {
                    projectSummaries.add(projectSummary)
                }
            }
        }

        val userJobs = if (isMiscellaneousProjectIncluded) {
            val issueJobs = miscUsers.map { user ->
                async(Dispatchers.Default) {
                    println("Pulling issues for user ${user.id}")
                    val issuesForUser = jiraApi.getIssuesResolved(
                        user.jiraId!!,
                        Clock.System.now().minus(duration),
                        Clock.System.now()
                    )
                    mutex.withLock {
                        miscIssueSet.addAll(issuesForUser)
                    }
                }
            }
            val prJobs = miscUsers.map { user ->
                async(Dispatchers.Default) {
                    println("Pulling PRs for user ${user.id}")
                    val prsForUser = gitHubApi.getMergedPullRequests(
                        user.gitHubId!!, gitHubOrganizationIds, Clock.System.now().minus(duration),
                        Clock.System.now()
                    )
                    mutex.withLock {
                        miscPrSet.addAll(prsForUser)
                    }
                }
            }
            issueJobs + prJobs
        } else {
            emptyList()
        }

        projectJobs.joinAll()
        userJobs.joinAll()
    }

    projectSummaries.forEach { projectSummary ->
        miscIssueSet.removeAll(projectSummary.issues)
        miscIssueSet.removeAll(projectSummary.milestones.map { it.issue })
        miscIssueSet.removeAll(projectSummary.milestones.flatMap { it.issues })
        miscPrSet.removeAll(projectSummary.durationMergedPullRequests)
    }
    projectSummaries.sortBy { it.project.title?.replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "") }

    val miscProject = Project(
        id = 123456789101112L,
        title = "📋 Other (Misc)",
        topLevelIssueKeys = miscIssueSet.map { it.key },
    )
    if (isMiscellaneousProjectIncluded) {
        projectSummaries.add(
            miscProject.createSummary(
                miscUsers.toSet(),
                gitHubApi,
                gitHubOrganizationIds,
                jiraApi,
                textSummarizer,
                duration,
                miscPrSet,
                true
            )
        )
    }

    val pagerDutyIncidentList: List<PagerDutyIncident>? =
        if (pagerDutyApi != null && pagerDutyServiceIds.isNotEmpty()) {
        val alertList = mutableListOf<PagerDutyIncident>()
        pagerDutyServiceIds.forEach { serviceId ->
            alertList += pagerDutyApi.getServicePages(serviceId, Clock.System.now().minus(duration), Clock.System.now())
        }
        alertList
    } else {
        null
    }

    return MultiProjectSummary(
        timeInPast.toLocalDateTime(TimeZone.UTC).date,
        Clock.System.now().toLocalDateTime(TimeZone.UTC).date,
        summaryName,
        projectSummaries,
        pagerDutyIncidentList,
    )
}

suspend fun Project.createSummary(
    users: Set<User>,
    gitHubApi: GitHubApi,
    gitHubOrganizationIds: List<String>,
    jiraApi: JiraApi,
    textSummarizer: TextSummarizer,
    duration: Duration,
    pullRequests: Set<com.github.karlsabo.github.Issue>,
    parentIssuesAreChildren: Boolean = false,
): ProjectSummary {
    val parentIssues = if (topLevelIssueKeys.isEmpty())
        mutableListOf()
    else {
        println("Getting issues for $topLevelIssueKeys")
        jiraApi.getIssues(topLevelIssueKeys).toMutableList()
    }

    val parentIssueKeys = parentIssues.map { it.key }

    val childIssues: MutableList<Issue> = if (parentIssuesAreChildren) {
        parentIssues
    } else {
        println("Getting child issues for $parentIssues")
        jiraApi.getChildIssues(parentIssueKeys).toMutableList()
    }

    val resolvedChildIssues =
        childIssues.filter {
            it.fields.resolutionDate != null && it.fields.resolutionDate >= Clock.System.now()
                .minus(duration) && it.isIssueOrBug()
        }

    val summary = if (resolvedChildIssues.isNotEmpty()) {
        val summaryRawInput = StringBuilder()
        summaryRawInput.appendLine("# Issues\n\n")
        resolvedChildIssues.forEach { issue ->
            summaryRawInput.appendLine("## ${issue.fields.summary}")
            summaryRawInput.appendLine("Assignee: ${issue.fields.assignee?.displayName}")
            summaryRawInput.appendLine("Description:\n````${issue.fields.description}````\n")
        }
        textSummarizer.summarize(summaryRawInput.toString())
    } else {
        "* No updates in the last ${duration.inWholeDays} days*"
    }

    // Get merged PRs related to these issues
    val mergedPrs = mutableSetOf(*pullRequests.toTypedArray())
    resolvedChildIssues.forEach { issue ->
        println("Searching PRs for ${issue.key}")
        mergedPrs += gitHubApi.searchPullRequestsByText(
            issue.key,
            gitHubOrganizationIds,
            Clock.System.now().minus(duration),
            Clock.System.now()
        )
    }

    val milestones = if (parentIssuesAreChildren) {
        emptySet()
    } else {
        parentIssues.plus(childIssues).toSet()
            .filter { it.isMilestone() }.map { milestoneIssue ->
                // Get direct child issues for this milestone
                val milestoneChildIssues = jiraApi.getDirectChildIssues(milestoneIssue.key)
                    .filter { issue -> issue.isIssueOrBug() }
                    .toSet()

                // For PRs, we would need to query GitHub API
                // This is a simplification - in a real implementation, you would need to
                // query GitHub for PRs that reference these issue keys
                val milestonePrs = mutableSetOf<com.github.karlsabo.github.Issue>()

                // Find the owner of the milestone
                val owner =
                    if (milestoneIssue.fields.assignee?.displayName != null && milestoneIssue.fields.assignee.displayName.isNotBlank()) {
                    // Try to find the user by name or email
                        users.firstOrNull { it.name == milestoneIssue.fields.assignee.displayName }
                } else if (projectLeadUserId != null) {
                    // Try to find the user by email
                    users.firstOrNull { it.email == projectLeadUserId }
                } else {
                    null
                }

                // Get recent comments for the milestone
                val milestoneCommentSet = mutableSetOf<Comment>()
                if (milestoneIssue.key.isNotBlank()) {
                    milestoneCommentSet.addAll(
                        jiraApi.getRecentComments(milestoneIssue.key, 5)
                    )
                }

                Milestone(
                    owner,
                    milestoneIssue,
                    milestoneChildIssues,
                    milestoneCommentSet,
                    milestoneChildIssues.filter { issue ->
                        issue.isIssueOrBug()
                                && (issue.fields.resolutionDate != null
                                && issue.fields.resolutionDate >= Clock.System.now().minus(duration)
                                || issue.fields.created != null && issue.fields.created >= Clock.System.now()
                            .minus(duration))
                    }.toSet(),
                    milestonePrs,
                )
            }.toSet()
    }

    return ProjectSummary(
        this,
        summary,
        childIssues.filter { it.isIssueOrBug() }.toSet(),
        childIssues.filter {
            it.isIssueOrBug()
                    && (it.fields.resolutionDate != null && it.fields.resolutionDate >= Clock.System.now()
                .minus(duration)
                    || it.fields.created != null && it.fields.created >= Clock.System.now().minus(duration))
        }
            .toSet(),
        mergedPrs,
        milestones,
        isTagMilestoneOwners,
    )
}
