package com.github.karlsabo.tools

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.projectmanagement.isMilestone
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.tools.model.Milestone
import com.github.karlsabo.tools.model.ProjectSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Creates a summary for a single project.
 *
 * @param users Users associated with this project (for milestone ownership)
 * @param gitHubApi API for fetching GitHub PR data
 * @param gitHubOrganizationIds GitHub organization IDs to search for PRs
 * @param projectManagementApi API for fetching project management data
 * @param textSummarizer Summarizer for generating issue summaries
 * @param duration Time period to look back for activity
 * @param pullRequests Additional PRs to include (for misc project)
 * @param parentIssuesAreChildren If true, treats parent issues as the children (for misc project)
 */
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
    val parentIssues = fetchParentIssues(projectManagementApi)
    val childIssues = fetchChildIssues(parentIssues, parentIssuesAreChildren, projectManagementApi)

    val resolvedChildIssues = childIssues.filter {
        it.completedAt != null && it.completedAt >= Clock.System.now().minus(duration) && it.isIssueOrBug()
    }

    val summary = generateSummaryText(resolvedChildIssues, textSummarizer, duration)
    val mergedPrs =
        fetchRelatedPullRequests(resolvedChildIssues, pullRequests, gitHubApi, gitHubOrganizationIds, duration)

    val milestones = if (parentIssuesAreChildren) {
        emptySet()
    } else {
        buildMilestones(parentIssues, childIssues, users, projectManagementApi, duration)
    }

    return ProjectSummary(
        this,
        summary,
        childIssues.filter { it.isIssueOrBug() }.toSet(),
        childIssues.filter {
            it.isIssueOrBug()
                    && (it.completedAt != null && it.completedAt >= Clock.System.now().minus(duration)
                    || it.createdAt != null && it.createdAt >= Clock.System.now().minus(duration))
        }.toSet(),
        mergedPrs,
        milestones,
        isTagMilestoneOwners,
    )
}

private suspend fun Project.fetchParentIssues(
    projectManagementApi: ProjectManagementApi,
): MutableList<ProjectIssue> {
    return if (topLevelIssueKeys.isEmpty()) {
        mutableListOf()
    } else {
        logger.debug { "Getting issues for $topLevelIssueKeys" }
        projectManagementApi.getIssues(topLevelIssueKeys).toMutableList()
    }
}

private suspend fun Project.fetchChildIssues(
    parentIssues: MutableList<ProjectIssue>,
    parentIssuesAreChildren: Boolean,
    projectManagementApi: ProjectManagementApi,
): MutableList<ProjectIssue> {
    return if (parentIssuesAreChildren) {
        parentIssues
    } else {
        val parentIssueKeys = parentIssues.map { it.key }
        logger.debug { "Getting child issues for $parentIssues" }
        projectManagementApi.getChildIssues(parentIssueKeys).toMutableList()
    }
}

private suspend fun generateSummaryText(
    resolvedChildIssues: List<ProjectIssue>,
    textSummarizer: TextSummarizer,
    duration: Duration,
): String {
    return if (resolvedChildIssues.isNotEmpty()) {
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
}

private suspend fun fetchRelatedPullRequests(
    resolvedChildIssues: List<ProjectIssue>,
    initialPullRequests: Set<com.github.karlsabo.github.Issue>,
    gitHubApi: GitHubApi,
    gitHubOrganizationIds: List<String>,
    duration: Duration,
): MutableSet<com.github.karlsabo.github.Issue> {
    val mergedPrs = mutableSetOf(*initialPullRequests.toTypedArray())
    resolvedChildIssues.forEach { issue ->
        logger.debug { "Searching PRs for ${issue.key}" }
        mergedPrs += gitHubApi.searchPullRequestsByText(
            issue.key,
            gitHubOrganizationIds,
            Clock.System.now().minus(duration),
            Clock.System.now()
        )
    }
    return mergedPrs
}

private suspend fun Project.buildMilestones(
    parentIssues: List<ProjectIssue>,
    childIssues: List<ProjectIssue>,
    users: Set<User>,
    projectManagementApi: ProjectManagementApi,
    duration: Duration,
): Set<Milestone> {
    return parentIssues.plus(childIssues).toSet()
        .filter { it.isMilestone() }
        .map { milestoneIssue ->
            buildMilestone(milestoneIssue, users, projectManagementApi, duration)
        }.toSet()
}

private suspend fun Project.buildMilestone(
    milestoneIssue: ProjectIssue,
    users: Set<User>,
    projectManagementApi: ProjectManagementApi,
    duration: Duration,
): Milestone {
    // Get direct child issues for this milestone
    val milestoneChildIssues = projectManagementApi.getDirectChildIssues(milestoneIssue.key)
        .filter { issue -> issue.isIssueOrBug() }
        .toSet()

    // Find the owner of the milestone
    val owner = findMilestoneOwner(milestoneIssue, users)

    // Get recent comments for the milestone
    val milestoneCommentSet = mutableSetOf<ProjectComment>()
    if (milestoneIssue.key.isNotBlank()) {
        milestoneCommentSet.addAll(
            projectManagementApi.getRecentComments(milestoneIssue.key, 5)
        )
    }

    return Milestone(
        owner,
        milestoneIssue,
        milestoneChildIssues,
        milestoneCommentSet,
        milestoneChildIssues.filter { issue ->
            issue.isIssueOrBug()
                    && (issue.completedAt != null
                    && issue.completedAt >= Clock.System.now().minus(duration)
                    || issue.createdAt != null && issue.createdAt >= Clock.System.now().minus(duration))
        }.toSet(),
        mutableSetOf(), // milestonePrs - placeholder for future implementation
    )
}

private fun Project.findMilestoneOwner(
    milestoneIssue: ProjectIssue,
    users: Set<User>,
): User? {
    return if (milestoneIssue.assigneeName != null && milestoneIssue.assigneeName.isNotBlank()) {
        users.firstOrNull { it.name == milestoneIssue.assigneeName }
    } else if (projectLeadUserId != null) {
        users.firstOrNull { it.email == projectLeadUserId }
    } else {
        null
    }
}
