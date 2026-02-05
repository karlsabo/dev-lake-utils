package com.github.karlsabo.tools

import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyIncident
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.text.TextSummarizer
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

/**
 * Creates a multi-project summary by aggregating data from various sources.
 *
 * @param projectManagementApi API for fetching project management data (Jira/Linear)
 * @param gitHubApi API for fetching GitHub PR data
 * @param gitHubOrganizationIds GitHub organization IDs to search for PRs
 * @param pagerDutyApi Optional API for fetching PagerDuty incidents
 * @param pagerDutyServiceIds PagerDuty service IDs to fetch incidents for
 * @param textSummarizer Summarizer for generating issue summaries
 * @param projects List of projects to include in the summary
 * @param duration Time period to look back for activity
 * @param users Users associated with projects
 * @param miscUsers Users to track for miscellaneous (unassigned) work
 * @param summaryName Name for the summary
 * @param isMiscellaneousProjectIncluded Whether to include a "misc" section for untracked work
 */
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
            collectMiscellaneousWork(
                miscUsers,
                projectManagementApi,
                gitHubApi,
                gitHubOrganizationIds,
                duration,
                mutex,
                miscIssueSet,
                miscPrSet
            )
        } else {
            emptyList()
        }

        projectJobs.joinAll()
        userJobs.joinAll()
    }

    // Remove items already captured in project summaries from misc sets
    projectSummaries.forEach { projectSummary ->
        miscIssueSet.removeAll(projectSummary.issues)
        miscIssueSet.removeAll(projectSummary.milestones.map { it.issue }.toSet())
        miscIssueSet.removeAll(projectSummary.milestones.flatMap { it.issues }.toSet())
        miscPrSet.removeAll(projectSummary.durationMergedPullRequests)
    }
    projectSummaries.sortBy { it.project.title?.replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "") }

    // Add miscellaneous project if enabled
    if (isMiscellaneousProjectIncluded) {
        val miscProject = Project(
            id = 123456789101112L,
            title = "📋 Other (Misc)",
            topLevelIssueKeys = miscIssueSet.map { it.key },
        )
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

    val pagerDutyIncidentList = fetchPagerDutyIncidents(
        pagerDutyApi,
        pagerDutyServiceIds,
        duration
    )

    return MultiProjectSummary(
        timeInPast.toLocalDateTime(TimeZone.UTC).date,
        Clock.System.now().toLocalDateTime(TimeZone.UTC).date,
        summaryName,
        projectSummaries,
        pagerDutyIncidentList,
    )
}

private suspend fun collectMiscellaneousWork(
    miscUsers: List<User>,
    projectManagementApi: ProjectManagementApi,
    gitHubApi: GitHubApi,
    gitHubOrganizationIds: List<String>,
    duration: Duration,
    mutex: Mutex,
    miscIssueSet: MutableSet<ProjectIssue>,
    miscPrSet: MutableSet<com.github.karlsabo.github.Issue>,
) = coroutineScope {
    val issueJobs = miscUsers.map { user ->
        async(Dispatchers.Default) {
            logger.info { "Pulling issues for user ${user.id}" }
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
}

private suspend fun fetchPagerDutyIncidents(
    pagerDutyApi: PagerDutyApi?,
    pagerDutyServiceIds: List<String>,
    duration: Duration,
): List<PagerDutyIncident>? {
    if (pagerDutyApi == null || pagerDutyServiceIds.isEmpty()) return null

    val alertList = mutableListOf<PagerDutyIncident>()
    pagerDutyServiceIds.forEach { serviceId ->
        alertList += pagerDutyApi.getServicePages(
            serviceId,
            Clock.System.now().minus(duration),
            Clock.System.now()
        )
    }
    return alertList
}
