package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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
              * Done: 2026-06-15

            # Project Atlas
            ## MVP
            * ENG-101 Ship ingestion
              * Done: 2026-06-15
            * ENG-102 Fix ingestion bug
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rendersIssuesWithoutProjectOrMilestoneUnderPlaceholderSections() {
        val issues = listOf(
            issue(
                key = "ENG-103",
                title = "Cleanup logs",
                projectName = null,
                milestoneName = null,
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # No project
            ## No milestone
            * ENG-103 Cleanup logs
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rendersIssueDoneDateFromCompletedAtUtcDate() {
        val issues = listOf(
            issue(
                key = "ENG-101",
                title = "Ship ingestion",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                completedAt = Instant.parse("2026-06-15T20:12:00Z"),
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Project Atlas
            ## MVP
            * ENG-101 Ship ingestion
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rendersInProgressDoneDateWhenCompletedAtIsMissing() {
        val issues = listOf(
            issue(
                key = "ENG-104",
                title = "Unfinished cleanup",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                completedAt = null,
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Project Atlas
            ## MVP
            * ENG-104 Unfinished cleanup
              * Done: in-progress
            """.trimIndent(),
            markdown,
        )
    }

    private fun issue(
        key: String,
        title: String,
        projectName: String?,
        milestoneName: String?,
        completedAt: Instant? = Instant.parse("2026-06-15T20:12:00Z"),
    ): ProjectIssue = ProjectIssue(
        id = key,
        key = key,
        title = title,
        completedAt = completedAt,
        projectName = projectName,
        milestoneName = milestoneName,
    )
}
