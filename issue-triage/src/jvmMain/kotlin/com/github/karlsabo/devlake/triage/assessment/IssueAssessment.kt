package com.github.karlsabo.devlake.triage.assessment

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class IssueAssessment(
    val difficulty: Int,
    val prNeeded: PrNeeded,
    val prReason: String,
    val rationale: String,
    val confidence: AssessmentConfidence,
    val model: String,
    val thinking: String,
    val sourceUpdatedAt: String?,
    val promptVersion: String,
    val status: AssessmentStatus,
) {
    init {
        require(difficulty in MIN_DIFFICULTY..MAX_DIFFICULTY) { "Difficulty must be between 1 and 10" }
        require(prReason.isNotBlank()) { "PR reason must not be blank" }
        require(rationale.isNotBlank()) { "Rationale must not be blank" }
        require(model.isNotBlank()) { "Assessment model must not be blank" }
        require(thinking.isNotBlank()) { "Assessment thinking level must not be blank" }
        require(promptVersion.isNotBlank()) { "Prompt version must not be blank" }
    }

    companion object {
        private const val MIN_DIFFICULTY = 1
        private const val MAX_DIFFICULTY = 10
        private val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        fun parse(json: String): IssueAssessment = strictJson.decodeFromString(json)
    }
}

@Serializable
internal enum class PrNeeded {
    Yes,
    No,
    Unclear,
}

@Serializable
internal enum class AssessmentConfidence {
    Low,
    Medium,
    High,
}

@Serializable
internal enum class AssessmentStatus {
    Assessed,
    Stale,
    Error,
}
