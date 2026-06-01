package com.github.karlsabo.linear

import com.github.karlsabo.common.pagination.collectCursorPaginated
import com.github.karlsabo.common.pagination.extractCursorPage
import com.github.karlsabo.linear.conversion.toUnifiedProjectMilestone
import com.github.karlsabo.linear.query.LinearQueryBuilder
import com.github.karlsabo.linear.query.LinearQueryBuilder.Companion.escapeGraphQlString
import com.github.karlsabo.tools.lenientJson
import com.github.karlsabo.projectmanagement.ProjectMilestone as UnifiedProjectMilestone

internal class LinearMilestoneReader(
    private val graphQlClient: LinearGraphQlClient,
    private val queryBuilder: LinearQueryBuilder,
) {
    suspend fun getMilestones(projectId: String): List<UnifiedProjectMilestone> {
        val escapedProjectId = escapeGraphQlString(projectId)
        return collectCursorPaginated(
            fetchPage = { cursor ->
                graphQlClient.execute(
                    queryBuilder.projectMilestones(escapedProjectId, LINEAR_MILESTONE_FIELDS, cursor),
                )
            },
            extractPage = { data -> data.extractCursorPage("project", "projectMilestones") },
            transform = { node ->
                lenientJson.decodeFromJsonElement(ProjectMilestone.serializer(), node).toUnifiedProjectMilestone()
            },
        )
    }
}
