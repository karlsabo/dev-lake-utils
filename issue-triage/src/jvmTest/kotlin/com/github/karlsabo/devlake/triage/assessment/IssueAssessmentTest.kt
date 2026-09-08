package com.github.karlsabo.devlake.triage.assessment

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssueAssessmentTest {
    @Test
    fun `parses the exact typed response contract`() {
        val assessment = IssueAssessment.parse(validJson)

        assertEquals(7, assessment.difficulty)
        assertEquals(PrNeeded.Yes, assessment.prNeeded)
        assertEquals(AssessmentConfidence.High, assessment.confidence)
        assertEquals(AssessmentStatus.Assessed, assessment.status)
    }

    @Test
    fun `rejects unknown fields`() {
        assertFailsWith<SerializationException> {
            IssueAssessment.parse(validJson.replace("{", "{\"extra\":true,"))
        }
    }

    @Test
    fun `rejects unknown enum values`() {
        assertFailsWith<SerializationException> {
            IssueAssessment.parse(validJson.replace("\"Yes\"", "\"Maybe\""))
        }
    }

    @Test
    fun `rejects out of range difficulty`() {
        assertFailsWith<IllegalArgumentException> {
            IssueAssessment.parse(validJson.replace("\"difficulty\":7", "\"difficulty\":11"))
        }
    }

    private companion object {
        val validJson = """
            {
              "difficulty":7,
              "prNeeded":"Yes",
              "prReason":"A code change is required",
              "rationale":"src/auth.kt:42 contains the affected logic",
              "confidence":"High",
              "model":"openai-codex/gpt-5.6-sol",
              "thinking":"medium",
              "sourceUpdatedAt":"2026-04-02T00:00:00Z",
              "promptVersion":"issue-triage-v1",
              "status":"Assessed"
            }
        """.trimIndent()
    }
}
