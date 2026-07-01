package com.github.karlsabo.linear.conversion

import com.github.karlsabo.linear.Issue
import com.github.karlsabo.linear.LINEAR_ISSUE_FIELDS
import com.github.karlsabo.tools.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class IssueConversionsTest {
    @Test
    fun linearProjectAndMilestoneMetadataAreMappedToProjectIssue() {
        val issue = lenientJson.decodeFromString<Issue>(
            """
            {
              "id": "issue-101",
              "identifier": "ENG-101",
              "title": "Ship ingestion",
              "project": {
                "id": "project-atlas-id",
                "name": "Project Atlas",
                "completedAt": "2026-06-28T14:00:00Z"
              },
              "projectMilestone": {
                "id": "mvp-id",
                "name": "MVP"
              }
            }
            """.trimIndent(),
        )

        val projectIssue = issue.toProjectIssue()

        assertEquals("project-atlas-id", projectIssue.projectId)
        assertEquals("Project Atlas", projectIssue.projectName)
        assertEquals(Instant.parse("2026-06-28T14:00:00Z"), projectIssue.projectFinalizedAt)
        assertEquals("mvp-id", projectIssue.milestoneId)
        assertEquals("MVP", projectIssue.milestoneName)
    }

    @Test
    fun canceledLinearProjectDateIsMappedAsProjectFinalizedAt() {
        val issue = lenientJson.decodeFromString<Issue>(
            """
            {
              "id": "issue-102",
              "identifier": "ENG-102",
              "title": "Stop project",
              "project": {
                "id": "project-atlas-id",
                "name": "Project Atlas",
                "canceledAt": "2026-06-29T10:00:00Z"
              }
            }
            """.trimIndent(),
        )

        val projectIssue = issue.toProjectIssue()

        assertEquals(Instant.parse("2026-06-29T10:00:00Z"), projectIssue.projectFinalizedAt)
    }

    @Test
    fun issueSelectionRequestsProjectAndMilestoneMetadata() {
        assertTrue(
            LINEAR_ISSUE_FIELDS.contains(
                """
                project {
                  id
                  name
                  completedAt
                  canceledAt
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            LINEAR_ISSUE_FIELDS.contains(
                """
                projectMilestone {
                  id
                  name
                }
                """.trimIndent(),
            ),
        )
    }
}
