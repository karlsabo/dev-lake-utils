package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.*
import com.github.karlsabo.devlake.dto.DevLakeSummary
import com.github.karlsabo.devlake.dto.PagerDutyAlert
import com.github.karlsabo.devlake.dto.Project
import com.github.karlsabo.jira.JiraApi
import com.github.karlsabo.jira.toPlainText
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
import java.net.URI
import java.sql.Date
import javax.sql.DataSource
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
    val issueChangeLogs: Set<IssueChangelog>,
    val milestoneComments: Set<IssueComment>,
    val durationIssues: Set<Issue>,
    val durationMergedPullRequests: Set<PullRequest>,
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
    val durationMergedPullRequests: Set<PullRequest>,
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
        this.milestones.sortedBy { it.issue.title }.forEach { milestone ->
            if (milestone.issue.resolutionDate != null && milestone.issue.resolutionDate < Clock.System.now()
                    .minus(14.days)
            ) return@forEach

            val complete = if (milestone.issue.resolutionDate == null) "" else "✅ "
            summary.appendLine("*$complete<${milestone.issue.url}|${milestone.issue.title}>: ${milestone.issue.assigneeName}*")
            summary.append(createSlackMarkdownProgressBar(milestone.issues, milestone.durationIssues))

            if (milestone.issue.resolutionDate == null) {
                val issuesResolved = milestone.durationIssues.filter { it.isCompleted() }
                if (issuesResolved.isNotEmpty()) {
                    summary.appendLine(
                        "📍 Issues resolved: ${
                            issuesResolved.joinToString(", ") { "<${it.url}|${it.issueKey}>" }
                        }"
                    )
                } else {
                    val changeCharacterLimit = 200
                    val lastChange = milestone.issueChangeLogs.sortedByDescending { it.createdDate }.firstOrNull()
                    val lastChangeDate = lastChange?.createdDate
                    val lastIssue = milestone.issues.sortedByDescending { it.resolutionDate }.firstOrNull()
                    val lastIssueResolutionDate = lastIssue?.resolutionDate

                    // Check for the most recent update from changelogs, issue resolutions, or comments
                    val lastComment = milestone.milestoneComments.sortedByDescending { it.createdDate }.firstOrNull()
                    val lastCommentDate = lastComment?.createdDate

                    val isStatusRecent: Boolean
                    // Determine which is the most recent update: changelog, issue resolution, or comment
                    if (lastChangeDate != null &&
                        (lastIssueResolutionDate == null || lastChange.createdDate > lastIssue.resolutionDate) &&
                        (lastCommentDate == null || lastChange.createdDate > lastCommentDate)
                    ) {
                        // Changelog is the most recent
                        val dateStr = lastChangeDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastChangeDate >= Clock.System.now().minus(14.days)
                        val warningEmoji = if (!isStatusRecent) "⚠️ " else ""
                        val changeDescription =
                            "${lastChange.originalToValue}".take(changeCharacterLimit) + if ("${lastChange.fieldName} to ${lastChange.originalToValue}".length > changeCharacterLimit) "..." else ""
                        summary.appendLine("${warningEmoji}🗓️ Last update $dateStr: *${lastChange.authorName}* \"$changeDescription\"")
                    } else if (lastCommentDate != null &&
                        (lastIssueResolutionDate == null || lastCommentDate > lastIssue.resolutionDate) &&
                        (lastChangeDate == null || lastCommentDate > lastChangeDate)
                    ) {
                        // Comment is the most recent
                        val dateStr = lastCommentDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastCommentDate >= Clock.System.now().minus(14.days)
                        val warningEmoji = if (!isStatusRecent) "⚠️ " else ""
                        val commentBody = lastComment.body?.take(changeCharacterLimit) ?: ""
                        val commentDescription =
                            commentBody + if ((lastComment.body?.length ?: 0) > changeCharacterLimit) "..." else ""
                        summary.appendLine("${warningEmoji}🗓️ Last update $dateStr: \"$commentDescription\"")
                    } else if (lastIssueResolutionDate != null) {
                        // Issue resolution is the most recent
                        val dateStr = lastIssueResolutionDate.toLocalDateTime(TimeZone.of("America/New_York")).date
                        isStatusRecent = lastIssueResolutionDate >= Clock.System.now().minus(14.days)
                        val warningEmoji =
                            if (!isStatusRecent) "⚠️ " else ""
                        summary.appendLine(
                            "$warningEmoji🗓️ Last update $dateStr: <${lastIssue.url}|${lastIssue.issueKey}> \"${
                                lastIssue.title?.take(
                                    changeCharacterLimit
                                )
                            }${if ((lastIssue.title?.length ?: 0) > changeCharacterLimit) "..." else ""}\""
                        )
                    } else {
                        isStatusRecent = false
                    }

                    if (milestone.issue.dueDate == null) {
                        if (milestone.assignee == null) {
                            summary.appendLine("‼️⚠️ This milestone doesn't have a due date or an assignee.")
                        } else {
                            summary.append(milestone.assignee.name)
                            if (isTagMilestoneAssignees) summary.append(" <@${milestone.assignee.slackId}>")
                            summary.appendLine(", please add a due date on the Epic")
                        }
                    } else if (!isStatusRecent && milestone.issue.dueDate.minus(90.days) < Clock.System.now()) {
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
                            issuesOpened.joinToString(", ") { "<${it.url}|${it.issueKey}>" }
                        }"
                    )
                }
                if (milestone.durationMergedPullRequests.isNotEmpty()) {
                    summary.appendLine("🔹 PRs merged: ${milestone.durationMergedPullRequests.joinToString(", ") { "<${it.url}|${it.pullRequestKey}>" }}")
                }
                summary.appendLine()
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
    dataSource: DataSource,
    jiraApi: JiraApi?,
    textSummarizer: TextSummarizer,
    projects: List<Project>,
    duration: Duration,
    users: List<User>,
    summaryName: String,
    isMiscellaneousProjectIncluded: Boolean,
    isPagerDutyIncluded: Boolean,
): DevLakeSummary {
    val timeInPast = Clock.System.now().minus(duration)
    val timeInPastSql = Date(timeInPast.toEpochMilliseconds())

    val mutex = Mutex()
    val projectSummaries = mutableListOf<ProjectSummary>()
    val miscIssueSet = mutableSetOf<Issue>()
    val miscPrSet = mutableSetOf<PullRequest>()

    coroutineScope {
        val projectJobs = projects.map { project ->
            async(Dispatchers.Default) {
                val projectSummary =
                    project.createSummary(users.toSet(), dataSource, jiraApi, textSummarizer, duration, emptySet())
                mutex.withLock {
                    projectSummaries.add(projectSummary)
                }
            }
        }

        val issueAccessor = IssueAccessorDb(dataSource)
        val pullRequestAccessor = PullRequestAccessorDb(dataSource)
        val userAccountAccessor = UserAccountAccessorDb(dataSource)

        val userJobs = if (isMiscellaneousProjectIncluded) {
            val issueJobs = users.map { user ->
                async(Dispatchers.Default) {
                    val userAccounts = userAccountAccessor.getUserAccountByUserId(user.id)
                    userAccounts.forEach {
                        val issuesForUser = issueAccessor.getIssuesByAssigneeIdAndAfterResolutionDate(
                            it.accountId,
                            Clock.System.now().minus(duration)
                        )
                        mutex.withLock {
                            miscIssueSet.addAll(issuesForUser)
                        }
                    }
                }
            }
            val prJobs = users.map { user ->
                async(Dispatchers.Default) {
                    val userAccounts = userAccountAccessor.getUserAccountByUserId(user.id)
                    userAccounts.forEach {
                        val prsForUser = pullRequestAccessor.getPullRequestsByAuthorIdAndAfterMergedDate(
                            it.accountId,
                            Clock.System.now().minus(duration)
                        )
                        mutex.withLock {
                            miscPrSet.addAll(prsForUser)
                        }
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
    if (isMiscellaneousProjectIncluded) {
        projectSummaries.add(
            miscProject.createSummary(
                users.toSet(),
                dataSource,
                jiraApi,
                textSummarizer,
                duration,
                miscPrSet,
                true
            )
        )
    }

    val pagerDutyAlertList: List<PagerDutyAlert>? = if (isPagerDutyIncluded) {
        val alertList = mutableListOf<PagerDutyAlert>()
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
                        alertList.add(
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
        alertList
    } else {
        null
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
    users: Set<User>,
    source: DataSource,
    jiraApi: JiraApi?,
    textSummarizer: TextSummarizer,
    duration: Duration,
    pullRequests: Set<PullRequest>,
    parentIssuesAreChildren: Boolean = false,
): ProjectSummary {
    val accountAccessor = AccountAccessorDb(source)
    val issueAccessor = IssueAccessorDb(source)
    val parentIssues = if (topLevelIssueKeys.isEmpty())
        mutableListOf()
    else
        issueAccessor.getIssuesByKey(this.topLevelIssueKeys)
            .toMutableList()
    if (topLevelIssueIds.isNotEmpty())
        parentIssues += issueAccessor.getIssuesById(topLevelIssueIds)
    val parentIssueIds = parentIssues.map { it.id } + topLevelIssueIds
    val childIssues: MutableList<Issue> =
        if (parentIssuesAreChildren) parentIssues else issueAccessor.getAllChildIssues(parentIssueIds).toMutableList()

    if (jqlToPullChildIssues != null && jiraApi != null) {
        val jiraIssues = jiraApi.runJql(jqlToPullChildIssues)
        jiraIssues.forEach { jiraIssue ->
            if (childIssues.none { it.issueKey == jiraIssue.issueKey }) {
                childIssues.add(jiraIssue.toIssue())
            }
        }
    }

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

    val pullRequestAccessorDb = PullRequestAccessorDb(source)
    val mergedPrs = pullRequestAccessorDb.getPullRequestsMergedSinceWithIssueKey(
        childIssues.map { it.issueKey },
        duration
    ).toMutableSet()
    mergedPrs.addAll(pullRequests)

    val issueChangelogAccessor = IssueChangelogAccessorDb(source)

    val milestones = if (parentIssuesAreChildren) {
        emptySet()
    } else {
        parentIssues.plus(childIssues).toSet()
            .filter { it.isMilestone() }.map { milestoneIssue ->
                val issues =
                    issueAccessor.getAllChildIssues(listOf(milestoneIssue.id)).filter { issue -> issue.isIssueOrBug() }
                        .toSet()
                val milestonePrs = pullRequestAccessorDb.getPullRequestsMergedSinceWithIssueKey(
                    issues.map { issue -> issue.issueKey },
                    duration
                ).toSet()

                val owner = if (milestoneIssue.assigneeId != null && milestoneIssue.assigneeId.isNotBlank()) {
                    val account = accountAccessor.getAccountById(milestoneIssue.assigneeId)
                    users.firstOrNull { it.email == account?.email }
                        ?: users.firstOrNull { it.name == milestoneIssue.assigneeName }
                } else if (projectLeadUserId != null) {
                    val account = accountAccessor.getAccountByEmail(projectLeadUserId)
                    users.firstOrNull { it.email == account?.email }
                } else {
                    null
                }

                val milestoneCommentSet = mutableSetOf<IssueComment>()
                if (jqlToPullChildIssues != null && jiraApi != null) {
                    milestoneCommentSet.addAll(
                        jiraApi.getRecentComments(milestoneIssue.issueKey, 5).map { it.toIssueComment() })
                }

                Milestone(
                    owner,
                    milestoneIssue,
                    issues,
                    issueChangelogAccessor.getPaginatedChangelogsByIssueIdsAndField(
                        issues.map { it.id }.toSet() + milestoneIssue.id,
                        setOf("Recent Comment"),
                        setOf("Automation for Jira"), // KARLFIXME load from a config
                        10,
                        0
                    ).toSet(),
                    milestoneCommentSet,
                    issues.filter { issue ->
                        issue.isIssueOrBug()
                            && (issue.resolutionDate != null
                            && issue.resolutionDate >= Clock.System.now().minus(duration)
                            || issue.createdDate != null && issue.createdDate >= Clock.System.now().minus(duration))
                    }
                        .toSet(),
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
                && (it.resolutionDate != null && it.resolutionDate >= Clock.System.now().minus(duration)
                || it.createdDate != null && it.createdDate >= Clock.System.now().minus(duration))
        }
            .toSet(),
        mergedPrs,
        milestones,
        isTagMilestoneOwners,
    )
}

private fun com.github.karlsabo.jira.Issue.toIssue(): Issue {
    val uri = URI(this.url!!)
    val url = uri.scheme + "://" + uri.authority + "/browse/${this.issueKey}"
    // TODO should look in _tool_jira_accounts tables
    val assigneeIdTranslated = if (assigneeId != null) "jira:JiraAccount:1:${this.assigneeId}" else null
    return Issue(
        id = this.id,
        url = url,
        iconUrl = this.iconUrl,
        issueKey = this.issueKey,
        title = this.title,
        description = this.description,
        epicKey = this.epicKey,
        type = this.type,
        originalType = this.originalType,
        status = this.status,
        originalStatus = this.originalStatus,
        resolutionDate = this.resolutionDate,
        createdDate = this.createdDate,
        updatedDate = this.updatedDate,
        leadTimeMinutes = this.leadTimeMinutes,
        parentIssueId = this.parentIssueId,
        priority = this.priority,
        storyPoint = this.storyPoint,
        originalEstimateMinutes = this.originalEstimateMinutes,
        timeSpentMinutes = this.timeSpentMinutes,
        timeRemainingMinutes = this.timeRemainingMinutes,
        creatorId = this.creatorId,
        creatorName = this.creatorName,
        assigneeId = assigneeIdTranslated,
        assigneeName = this.assigneeName,
        severity = this.severity,
        component = this.component,
        originalProject = this.originalProject,
        urgency = this.urgency,
        isSubtask = this.isSubtask,
        rawDataParams = null,
        rawDataTable = null,
        rawDataId = null,
        rawDataRemark = null,
        dueDate = this.dueDate,
    )
}

private fun com.github.karlsabo.jira.Comment.toIssueComment(): IssueComment {
    return IssueComment(
        id = this.id,
        issueId = this.id,
        accountId = this.author.accountId,
        body = this.body.toPlainText(),
        createdDate = this.created,
        updatedDate = this.updated,
    )
}
