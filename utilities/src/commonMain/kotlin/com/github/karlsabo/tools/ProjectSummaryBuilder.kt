package com.github.karlsabo.tools

import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.tools.model.ProjectSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import com.github.karlsabo.github.Issue as GitHubIssue

private val logger = KotlinLogging.logger {}

data class ProjectSummaryDependencies(
    val gitHubApi: GitHubPullRequestSearchApi,
    val gitHubOrganizationIds: List<String>,
    val projectManagementApi: ProjectManagementApi,
    val textSummarizer: TextSummarizer,
)

data class ProjectSummaryRequest(
    val users: Set<User>,
    val dependencies: ProjectSummaryDependencies,
    val duration: Duration,
    val pullRequests: Set<GitHubIssue> = emptySet(),
    val parentIssuesAreChildren: Boolean = false,
)

suspend fun Project.createSummary(request: ProjectSummaryRequest): ProjectSummary {
    val dependencies = request.dependencies
    val (issueKeys, projectIds) = topLevelIssueIds.partition { issueIdentifierPattern.matches(it) }
    val hasProjectIds = projectIds.isNotEmpty()

    val parentIssues = fetchParentIssues(issueKeys, projectIds, dependencies.projectManagementApi)
    val effectiveParentIssuesAreChildren = request.parentIssuesAreChildren || hasProjectIds
    val childIssues = fetchChildIssues(
        parentIssues = parentIssues,
        parentIssuesAreChildren = effectiveParentIssuesAreChildren,
        projectManagementApi = dependencies.projectManagementApi,
    )

    val resolvedChildIssues = childIssues.filterResolvedIssues(request.duration)
    val summary = generateSummaryText(resolvedChildIssues, dependencies.textSummarizer, request.duration)
    val mergedPrs = fetchRelatedPullRequests(resolvedChildIssues, request)
    val milestones = buildProjectMilestones(parentIssues, childIssues, request, effectiveParentIssuesAreChildren)

    return ProjectSummary(
        this,
        summary,
        childIssues.filter { it.isIssueOrBug() }.toSet(),
        childIssues.filterRecentIssues(request.duration),
        mergedPrs,
        milestones,
        isTagMilestoneOwners,
    )
}

private val issueIdentifierPattern = Regex("^[A-Za-z]+-\\d+$")

private suspend fun fetchParentIssues(
    issueKeys: List<String>,
    projectIds: List<String>,
    projectManagementApi: ProjectManagementApi,
): MutableList<ProjectIssue> {
    val issues = mutableListOf<ProjectIssue>()
    if (issueKeys.isNotEmpty()) {
        logger.debug { "Getting issues for $issueKeys" }
        issues += projectManagementApi.getIssues(issueKeys)
    }
    if (projectIds.isNotEmpty()) {
        logger.debug { "Getting issues for projects $projectIds" }
        issues += projectManagementApi.getChildIssues(projectIds)
    }
    return issues
}

private suspend fun fetchChildIssues(
    parentIssues: List<ProjectIssue>,
    parentIssuesAreChildren: Boolean,
    projectManagementApi: ProjectManagementApi,
): MutableList<ProjectIssue> = if (parentIssuesAreChildren) {
    parentIssues.toMutableList()
} else {
    val parentIssueKeys = parentIssues.map { it.key }
    logger.debug { "Getting child issues for $parentIssues" }
    projectManagementApi.getChildIssues(parentIssueKeys).toMutableList()
}

private suspend fun generateSummaryText(
    resolvedChildIssues: List<ProjectIssue>,
    textSummarizer: TextSummarizer,
    duration: Duration,
): String = if (resolvedChildIssues.isNotEmpty()) {
    val summaryRawInput = StringBuilder()
    summaryRawInput.appendLine("# Issues\n")
    resolvedChildIssues.forEach { issue ->
        summaryRawInput.appendLine("## ${issue.title}")
        summaryRawInput.appendLine("Assignee: ${issue.assigneeName}")
        summaryRawInput.appendLine("Description:\n````${issue.description}````\n")
    }
    textSummarizer.summarize(summaryRawInput.toString())
} else {
    "* No updates in the last ${duration.inWholeDays} days*"
}

private suspend fun fetchRelatedPullRequests(
    resolvedChildIssues: List<ProjectIssue>,
    request: ProjectSummaryRequest,
): MutableSet<GitHubIssue> {
    val dependencies = request.dependencies
    val mergedPrs = request.pullRequests.toMutableSet()
    resolvedChildIssues.forEach { issue ->
        logger.debug { "Searching PRs for ${issue.key}" }
        mergedPrs += dependencies.gitHubApi.searchPullRequestsByText(
            issue.key,
            dependencies.gitHubOrganizationIds,
            Clock.System.now().minus(request.duration),
            Clock.System.now(),
        )
    }
    return mergedPrs
}
