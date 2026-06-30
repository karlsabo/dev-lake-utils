package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class UserLinearProjectsMarkdownTest {
    @Test
    fun rendersIssuesGroupedByProjectAndMilestoneInDeterministicOrder() {
        val issues = listOf(
            issue(
                key = "ENG-102",
                title = "Fix ingestion bug",
                projectName = "Project Atlas",
                milestoneName = "MVP",
            ),
            issue(
                key = "OPS-7",
                title = "Rotate tokens",
                projectName = "Operations",
                milestoneName = "Hardening",
            ),
            issue(
                key = "ENG-101",
                title = "Ship ingestion",
                projectName = "Project Atlas",
                milestoneName = "MVP",
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Operations
            ## Hardening
            * OPS-7 Rotate tokens

            # Project Atlas
            ## MVP
            * ENG-101 Ship ingestion
            * ENG-102 Fix ingestion bug
            """.trimIndent(),
            markdown,
        )
    }

    private fun issue(
        key: String,
        title: String,
        projectName: String,
        milestoneName: String,
    ): ProjectIssue = ProjectIssue(
        id = key,
        key = key,
        title = title,
        completedAt = Clock.System.now(),
        projectName = projectName,
        milestoneName = milestoneName,
    )
}
