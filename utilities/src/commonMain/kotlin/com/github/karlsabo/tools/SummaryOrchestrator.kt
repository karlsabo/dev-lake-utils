package com.github.karlsabo.tools

import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.pagerduty.PagerDutyApi
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
import com.github.karlsabo.github.Issue as GitHubIssue

private val logger = KotlinLogging.logger {}

private const val MISCELLANEOUS_PROJECT_ID = 123456789101112L

data class SummarySources(
    val projectManagementApi: ProjectManagementApi,
    val gitHubApi: GitHubPullRequestSearchApi,
    val gitHubOrganizationIds: List<String>,
    val pagerDutyApi: PagerDutyApi?,
    val pagerDutyServiceIds: List<String>,
    val textSummarizer: TextSummarizer,
)

data class SummaryOptions(
    val summaryName: String,
    val isMiscellaneousProjectIncluded: Boolean,
)

data class CreateSummaryRequest(
    val sources: SummarySources,
    val projects: List<Project>,
    val duration: Duration,
    val users: List<User>,
    val miscUsers: List<User>,
    val options: SummaryOptions,
)

suspend fun createSummary(request: CreateSummaryRequest): MultiProjectSummary {
    val timeInPast = Clock.System.now().minus(request.duration)
    val summaryWork = collectSummaryWork(request)

    removeProjectWorkFromMiscellaneous(summaryWork)
    summaryWork.projectSummaries.sortByProjectTitle()
    addMiscellaneousProjectSummary(request, summaryWork)

    return MultiProjectSummary(
        timeInPast.toLocalDateTime(TimeZone.UTC).date,
        Clock.System.now().toLocalDateTime(TimeZone.UTC).date,
        request.options.summaryName,
        summaryWork.projectSummaries,
        fetchPagerDutyIncidents(request.sources, request.duration),
    )
}

private data class SummaryWork(
    val projectSummaries: MutableList<ProjectSummary> = mutableListOf(),
    val miscIssues: MutableSet<ProjectIssue> = mutableSetOf(),
    val miscPullRequests: MutableSet<GitHubIssue> = mutableSetOf(),
)

private suspend fun collectSummaryWork(request: CreateSummaryRequest): SummaryWork = coroutineScope {
    val mutex = Mutex()
    val work = SummaryWork()
    val projectJobs = request.projects.map { project ->
        async(Dispatchers.Default) {
            val projectSummary = createProjectSummary(project, request)
            mutex.withLock { work.projectSummaries.add(projectSummary) }
        }
    }

    if (request.options.isMiscellaneousProjectIncluded) {
        collectMiscellaneousWork(
            request = MiscWorkRequest(request.miscUsers, request.sources, request.duration),
            accumulator = MiscWorkAccumulator(mutex, work.miscIssues, work.miscPullRequests),
        )
    }

    projectJobs.joinAll()
    work
}

private suspend fun createProjectSummary(
    project: Project,
    request: CreateSummaryRequest,
): ProjectSummary {
    logger.info { "Creating summary for project ${project.title}" }
    return project.createSummary(
        ProjectSummaryRequest(
            users = request.users.toSet(),
            dependencies = request.sources.toProjectSummaryDependencies(),
            duration = request.duration,
        ),
    )
}

private fun SummarySources.toProjectSummaryDependencies(): ProjectSummaryDependencies = ProjectSummaryDependencies(
    gitHubApi = gitHubApi,
    gitHubOrganizationIds = gitHubOrganizationIds,
    projectManagementApi = projectManagementApi,
    textSummarizer = textSummarizer,
)

private fun removeProjectWorkFromMiscellaneous(summaryWork: SummaryWork) {
    summaryWork.projectSummaries.forEach { projectSummary ->
        summaryWork.miscIssues.removeAll(projectSummary.issues)
        summaryWork.miscIssues.removeAll(projectSummary.milestones.map { it.issue }.toSet())
        summaryWork.miscIssues.removeAll(projectSummary.milestones.flatMap { it.issues }.toSet())
        summaryWork.miscPullRequests.removeAll(projectSummary.durationMergedPullRequests)
    }
}

private fun MutableList<ProjectSummary>.sortByProjectTitle() {
    sortBy { projectSummary -> projectSummary.project.title?.replaceFirst(Regex("""^[^\p{L}\p{N}]+"""), "") }
}

private suspend fun addMiscellaneousProjectSummary(
    request: CreateSummaryRequest,
    summaryWork: SummaryWork,
) {
    if (!request.options.isMiscellaneousProjectIncluded) return

    val miscProject = Project(
        id = MISCELLANEOUS_PROJECT_ID,
        title = "📋 Other (Misc)",
        topLevelIssueIds = summaryWork.miscIssues.map { it.key },
    )
    summaryWork.projectSummaries.add(
        miscProject.createSummary(
            ProjectSummaryRequest(
                users = request.miscUsers.toSet(),
                dependencies = request.sources.toProjectSummaryDependencies(),
                duration = request.duration,
                pullRequests = summaryWork.miscPullRequests,
                parentIssuesAreChildren = true,
            ),
        ),
    )
}
