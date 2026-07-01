package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val NO_PROJECT = "No project"
private const val NO_MILESTONE = "No milestone"
private const val IN_PROGRESS = "in-progress"

fun renderUserLinearProjectsMarkdown(issues: List<ProjectIssue>): String {
    val sortedIssues = issues
        .map { it.toMarkdownIssue() }
        .sortedWith(compareBy<MarkdownIssue> { it.milestoneName }.thenBy { it.key })

    return sortedIssues
        .groupBy { it.projectGroupKey() }
        .map { (projectGroupKey, projectIssues) -> projectIssues.toMarkdownProject(projectGroupKey) }
        .sortedWith(
            compareBy<MarkdownProject> { it.doneDate == null }
                .thenBy { !it.hasProject }
                .thenBy { it.doneDate }
                .thenBy { it.name }
                .thenBy { it.groupKey.value },
        )
        .map { project ->
            buildList {
                add("# ${project.name}")
                if (project.hasProject) {
                    add("* Done: ${project.doneDate ?: IN_PROGRESS}")
                }
                project.issues.groupBy { it.milestoneName }.forEach { (milestoneName, milestoneIssues) ->
                    add("## $milestoneName")
                    milestoneIssues.forEach { issue ->
                        add("* ${issue.key} ${issue.title}")
                        add("  * Done: ${issue.doneDate}")
                    }
                }
            }.joinToString("\n")
        }
        .joinToString("\n\n")
}

private data class MarkdownIssue(
    val projectId: String?,
    val projectName: String,
    val hasProject: Boolean,
    val projectDoneDate: String?,
    val milestoneName: String,
    val key: String,
    val title: String,
    val doneDate: String,
)

private data class MarkdownProject(
    val groupKey: ProjectGroupKey,
    val name: String,
    val hasProject: Boolean,
    val doneDate: String?,
    val issues: List<MarkdownIssue>,
)

private fun ProjectIssue.toMarkdownIssue(): MarkdownIssue {
    require(key.isNotBlank()) { "Project issue id=$id has a blank key" }

    return MarkdownIssue(
        projectId = projectId?.takeUnless { it.isBlank() },
        projectName = projectName.placeholderIfBlank(NO_PROJECT),
        hasProject = !projectId.isNullOrBlank() || !projectName.isNullOrBlank(),
        projectDoneDate = projectFinalizedAt?.toUtcDateOnly(),
        milestoneName = milestoneName.placeholderIfBlank(NO_MILESTONE),
        key = key,
        title = requireNotNull(title) { "Project issue $key is missing title" },
        doneDate = completedAt?.toUtcDateOnly() ?: IN_PROGRESS,
    )
}

private enum class ProjectGroupKeyType { ID, NAME }

private data class ProjectGroupKey(
    val type: ProjectGroupKeyType,
    val value: String,
)

private fun MarkdownIssue.projectGroupKey(): ProjectGroupKey = projectId
    ?.let { ProjectGroupKey(ProjectGroupKeyType.ID, it) }
    ?: ProjectGroupKey(ProjectGroupKeyType.NAME, projectName)

private fun List<MarkdownIssue>.toMarkdownProject(groupKey: ProjectGroupKey): MarkdownProject {
    val hasProject = any { it.hasProject }
    return MarkdownProject(
        groupKey = groupKey,
        name = first().projectName,
        hasProject = hasProject,
        doneDate = if (hasProject) projectDoneDate() else null,
        issues = this,
    )
}

private fun List<MarkdownIssue>.projectDoneDate(): String? {
    val dates = mapNotNull { it.projectDoneDate }.distinct()
    require(dates.size <= 1) { "Project ${first().projectName} has conflicting finalized dates" }
    return dates.singleOrNull()
}

private fun Instant.toUtcDateOnly(): String = toLocalDateTime(TimeZone.UTC).date.toString()

private fun String?.placeholderIfBlank(placeholder: String): String = if (isNullOrBlank()) placeholder else this
