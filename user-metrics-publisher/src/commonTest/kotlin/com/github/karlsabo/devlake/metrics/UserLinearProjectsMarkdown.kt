package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue

private const val NO_PROJECT = "No project"
private const val NO_MILESTONE = "No milestone"

fun renderUserLinearProjectsMarkdown(issues: List<ProjectIssue>): String {
    val sortedIssues = issues
        .map { it.toMarkdownIssue() }
        .sortedWith(
            compareBy<MarkdownIssue> { it.projectName }
                .thenBy { it.milestoneName }
                .thenBy { it.key },
        )

    return sortedIssues
        .groupBy { it.projectName }
        .map { (projectName, projectIssues) ->
            buildList {
                add("# $projectName")
                projectIssues.groupBy { it.milestoneName }.forEach { (milestoneName, milestoneIssues) ->
                    add("## $milestoneName")
                    milestoneIssues.forEach { issue ->
                        add("* ${issue.key} ${issue.title}")
                    }
                }
            }.joinToString("\n")
        }
        .joinToString("\n\n")
}

private data class MarkdownIssue(
    val projectName: String,
    val milestoneName: String,
    val key: String,
    val title: String,
)

private fun ProjectIssue.toMarkdownIssue(): MarkdownIssue {
    require(key.isNotBlank()) { "Project issue id=$id has a blank key" }

    return MarkdownIssue(
        projectName = projectName.placeholderIfBlank(NO_PROJECT),
        milestoneName = milestoneName.placeholderIfBlank(NO_MILESTONE),
        key = key,
        title = requireNotNull(title) { "Project issue $key is missing title" },
    )
}

private fun String?.placeholderIfBlank(placeholder: String): String =
    if (isNullOrBlank()) placeholder else this
