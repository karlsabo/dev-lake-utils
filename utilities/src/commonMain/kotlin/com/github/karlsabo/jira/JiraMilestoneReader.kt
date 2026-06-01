package com.github.karlsabo.jira

import com.github.karlsabo.jira.conversion.toProjectMilestone
import com.github.karlsabo.projectmanagement.ProjectMilestone

internal class JiraMilestoneReader(
    private val jqlSearch: JiraJqlSearchClient,
) {
    suspend fun getMilestones(projectId: String): List<ProjectMilestone> = jqlSearch.run(
        "project = \"$projectId\" AND issuetype = ${JiraConstants.ISSUE_TYPE_EPIC} ORDER BY created DESC",
    ) { issue ->
        issue.toProjectMilestone()
    }
}
