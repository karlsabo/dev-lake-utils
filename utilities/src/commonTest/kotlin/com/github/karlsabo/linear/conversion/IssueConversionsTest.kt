package com.github.karlsabo.linear.conversion

import com.github.karlsabo.linear.Issue
import com.github.karlsabo.linear.LINEAR_ISSUE_FIELDS
import com.github.karlsabo.tools.lenientJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                "name": "Project Atlas"
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
        assertEquals("mvp-id", projectIssue.milestoneId)
        assertEquals("MVP", projectIssue.milestoneName)
    }

    @Test
    fun issueSelectionRequestsProjectAndMilestoneMetadata() {
        assertTrue(
            LINEAR_ISSUE_FIELDS.contains(
                """
                project {
                  id
                  name
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
