package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.projectmanagement.ProjectIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            * Done: in-progress
            ## Hardening
            * OPS-7 Rotate tokens
              * Done: 2026-06-15

            # Project Atlas
            * Done: in-progress
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
            * Done: in-progress
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
            * Done: in-progress
            ## MVP
            * ENG-104 Unfinished cleanup
              * Done: in-progress
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rendersProjectDoneDateOrInProgressUnderProjectHeading() {
        val issues = listOf(
            finalizedIssue(
                key = "ENG-101",
                title = "Ship ingestion",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-28T14:00:00Z"),
            ),
            issue(
                key = "OPS-7",
                title = "Rotate tokens",
                projectName = "Operations",
                milestoneName = "Hardening",
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Project Atlas
            * Done: 2026-06-28
            ## MVP
            * ENG-101 Ship ingestion
              * Done: 2026-06-15

            # Operations
            * Done: in-progress
            ## Hardening
            * OPS-7 Rotate tokens
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun sortsProjectsByDoneDateAscendingWithInProgressAndNoProjectLast() {
        val issues = listOf(
            issue(
                key = "GAM-1",
                title = "Keep iterating",
                projectName = "Project Gamma",
                milestoneName = "MVP",
            ),
            finalizedIssue(
                key = "ALP-1",
                title = "Finish alpha",
                projectName = "Project Alpha",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-10T14:00:00Z"),
            ),
            issue(
                key = "NOP-1",
                title = "Triage loose work",
                projectName = null,
                milestoneName = "MVP",
            ),
            finalizedIssue(
                key = "BET-1",
                title = "Finish beta",
                projectName = "Project Beta",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-01T14:00:00Z"),
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Project Beta
            * Done: 2026-06-01
            ## MVP
            * BET-1 Finish beta
              * Done: 2026-06-15

            # Project Alpha
            * Done: 2026-06-10
            ## MVP
            * ALP-1 Finish alpha
              * Done: 2026-06-15

            # Project Gamma
            * Done: in-progress
            ## MVP
            * GAM-1 Keep iterating
              * Done: 2026-06-15

            # No project
            ## MVP
            * NOP-1 Triage loose work
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rendersKnownProjectDoneDateWhenOtherProjectIssuesHaveNoFinalizedDate() {
        val issues = listOf(
            finalizedIssue(
                key = "ENG-101",
                title = "Ship ingestion",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-28T14:00:00Z"),
            ),
            issue(
                key = "ENG-102",
                title = "Fix ingestion bug",
                projectName = "Project Atlas",
                milestoneName = "MVP",
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Project Atlas
            * Done: 2026-06-28
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
    fun rendersProjectsWithSameNameAsSeparateSectionsByProjectId() {
        val issues = listOf(
            ProjectIssue(
                id = "ENG-102",
                key = "ENG-102",
                title = "Ship retry",
                completedAt = Instant.parse("2026-06-15T20:12:00Z"),
                projectId = "project-b",
                projectName = "Launch",
                projectFinalizedAt = Instant.parse("2026-06-29T14:00:00Z"),
                milestoneName = "MVP",
            ),
            ProjectIssue(
                id = "ENG-101",
                key = "ENG-101",
                title = "Ship first pass",
                completedAt = Instant.parse("2026-06-15T20:12:00Z"),
                projectId = "project-a",
                projectName = "Launch",
                projectFinalizedAt = Instant.parse("2026-06-28T14:00:00Z"),
                milestoneName = "MVP",
            ),
        )

        val markdown = renderUserLinearProjectsMarkdown(issues)

        assertEquals(
            """
            # Launch
            * Done: 2026-06-28
            ## MVP
            * ENG-101 Ship first pass
              * Done: 2026-06-15

            # Launch
            * Done: 2026-06-29
            ## MVP
            * ENG-102 Ship retry
              * Done: 2026-06-15
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun rejectsConflictingProjectDoneDates() {
        val issues = listOf(
            finalizedIssue(
                key = "ENG-101",
                title = "Ship ingestion",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-28T14:00:00Z"),
            ),
            finalizedIssue(
                key = "ENG-102",
                title = "Fix ingestion bug",
                projectName = "Project Atlas",
                milestoneName = "MVP",
                projectFinalizedAt = Instant.parse("2026-06-29T14:00:00Z"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            renderUserLinearProjectsMarkdown(issues)
        }
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
        projectId = null,
        projectName = projectName,
        projectFinalizedAt = null,
        milestoneName = milestoneName,
    )

    private fun finalizedIssue(
        key: String,
        title: String,
        projectName: String?,
        milestoneName: String?,
        projectFinalizedAt: Instant,
    ): ProjectIssue = ProjectIssue(
        id = key,
        key = key,
        title = title,
        completedAt = Instant.parse("2026-06-15T20:12:00Z"),
        projectId = null,
        projectName = projectName,
        projectFinalizedAt = projectFinalizedAt,
        milestoneName = milestoneName,
    )
}
