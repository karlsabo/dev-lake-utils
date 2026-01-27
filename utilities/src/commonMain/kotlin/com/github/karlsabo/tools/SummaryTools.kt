package com.github.karlsabo.tools

import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyIncident
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.projectmanagement.isMilestone
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.tools.model.Milestone
import com.github.karlsabo.tools.model.ProjectSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

suspend fun createSummary(
    projectManagementApi: ProjectManagementApi,
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
    val miscIssueSet = mutableSetOf<ProjectIssue>()
    val miscPrSet = mutableSetOf<com.github.karlsabo.github.Issue>()

    coroutineScope {
        val projectJobs = projects.map { project ->
            async(Dispatchers.Default) {
                logger.info { "Creating summary for project ${project.title}" }
                val projectSummary =
                    project.createSummary(
                        users.toSet(),
                        gitHubApi,
                        gitHubOrganizationIds,
                        projectManagementApi,
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
                    logger.info { "Pulling issues for user ${user.id}" }
                    // Use the appropriate user ID field based on what's available
                    val userId = user.jiraId ?: user.id
                    val issuesForUser = projectManagementApi.getIssuesResolved(
                        userId,
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
                    logger.info { "Pulling PRs for user ${user.id}" }
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
        miscIssueSet.removeAll(projectSummary.milestones.map { it.issue }.toSet())
        miscIssueSet.removeAll(projectSummary.milestones.flatMap { it.issues }.toSet())
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
                projectManagementApi,
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
    projectManagementApi: ProjectManagementApi,
    textSummarizer: TextSummarizer,
    duration: Duration,
    pullRequests: Set<com.github.karlsabo.github.Issue>,
    parentIssuesAreChildren: Boolean = false,
): ProjectSummary {
    val parentIssues = if (topLevelIssueKeys.isEmpty())
        mutableListOf()
    else {
        logger.debug { "Getting issues for $topLevelIssueKeys" }
        projectManagementApi.getIssues(topLevelIssueKeys).toMutableList()
    }

    val parentIssueKeys = parentIssues.map { it.key }

    val childIssues: MutableList<ProjectIssue> = if (parentIssuesAreChildren) {
        parentIssues
    } else {
        logger.debug { "Getting child issues for $parentIssues" }
        projectManagementApi.getChildIssues(parentIssueKeys).toMutableList()
    }

    val resolvedChildIssues =
        childIssues.filter {
            it.completedAt != null && it.completedAt >= Clock.System.now()
                .minus(duration) && it.isIssueOrBug()
        }

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

    // Get merged PRs related to these issues
    val mergedPrs = mutableSetOf(*pullRequests.toTypedArray())
    resolvedChildIssues.forEach { issue ->
        logger.debug { "Searching PRs for ${issue.key}" }
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
                val milestoneChildIssues = projectManagementApi.getDirectChildIssues(milestoneIssue.key)
                    .filter { issue -> issue.isIssueOrBug() }
                    .toSet()

                // For PRs, we would need to query GitHub API
                // This is a simplification - in a real implementation, you would need to
                // query GitHub for PRs that reference these issue keys
                val milestonePrs = mutableSetOf<com.github.karlsabo.github.Issue>()

                // Find the owner of the milestone
                val owner =
                    if (milestoneIssue.assigneeName != null && milestoneIssue.assigneeName.isNotBlank()) {
                        // Try to find the user by name
                        users.firstOrNull { it.name == milestoneIssue.assigneeName }
                } else if (projectLeadUserId != null) {
                    // Try to find the user by email
                    users.firstOrNull { it.email == projectLeadUserId }
                } else {
                    null
                }

                // Get recent comments for the milestone
                val milestoneCommentSet = mutableSetOf<ProjectComment>()
                if (milestoneIssue.key.isNotBlank()) {
                    milestoneCommentSet.addAll(
                        projectManagementApi.getRecentComments(milestoneIssue.key, 5)
                    )
                }

                Milestone(
                    owner,
                    milestoneIssue,
                    milestoneChildIssues,
                    milestoneCommentSet,
                    milestoneChildIssues.filter { issue ->
                        issue.isIssueOrBug()
                                && (issue.completedAt != null
                                && issue.completedAt >= Clock.System.now().minus(duration)
                                || issue.createdAt != null && issue.createdAt >= Clock.System.now()
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
                    && (it.completedAt != null && it.completedAt >= Clock.System.now()
                .minus(duration)
                    || it.createdAt != null && it.createdAt >= Clock.System.now().minus(duration))
        }
            .toSet(),
        mergedPrs,
        milestones,
        isTagMilestoneOwners,
    )
}
