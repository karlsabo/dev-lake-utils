package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue

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
        projectName = requireNotNull(projectName) { "Project issue $key is missing projectName" },
        milestoneName = requireNotNull(milestoneName) { "Project issue $key is missing milestoneName" },
        key = key,
        title = requireNotNull(title) { "Project issue $key is missing title" },
    )
}
