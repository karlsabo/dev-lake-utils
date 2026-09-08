package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.AssessmentStatus
import com.github.karlsabo.devlake.triage.assessment.IssueAssessment

internal enum class ColumnOwnership {
    LINEAR,
    EDITABLE,
    TECHNICAL,
}

internal enum class TriageColumn(
    val header: String,
    val ownership: ColumnOwnership,
) {
    LINEAR_ID("Linear ID", ColumnOwnership.TECHNICAL),
    TICKET_ID("Ticket ID", ColumnOwnership.LINEAR),
    TITLE("Title", ColumnOwnership.LINEAR),
    URL("URL", ColumnOwnership.LINEAR),
    STATE("State", ColumnOwnership.LINEAR),
    KTLO_SOURCE("KTLO source", ColumnOwnership.LINEAR),
    UPDATED_AT("Updated at", ColumnOwnership.LINEAR),
    DIFFICULTY("Difficulty", ColumnOwnership.EDITABLE),
    PR_NEEDED("PR needed?", ColumnOwnership.EDITABLE),
    PR_REASON("PR reason", ColumnOwnership.EDITABLE),
    RATIONALE("Code evidence / rationale", ColumnOwnership.EDITABLE),
    CONFIDENCE("Confidence", ColumnOwnership.EDITABLE),
    ASSESSMENT_MODEL("Assessment model", ColumnOwnership.TECHNICAL),
    THINKING_LEVEL("Thinking level", ColumnOwnership.TECHNICAL),
    ASSESSMENT_SOURCE_UPDATED_AT("Assessment source updated at", ColumnOwnership.TECHNICAL),
    PROMPT_VERSION("Prompt version", ColumnOwnership.TECHNICAL),
    ASSESSMENT_STATUS("Assessment status", ColumnOwnership.TECHNICAL),
}

internal data class TriageAssessmentCells(
    val difficulty: String,
    val prNeeded: String,
    val prReason: String,
    val rationale: String,
    val confidence: String,
    val model: String,
    val thinking: String,
    val sourceUpdatedAt: String,
    val promptVersion: String,
    val status: String,
) {
    fun values(): List<String> = listOf(
        difficulty,
        prNeeded,
        prReason,
        rationale,
        confidence,
        model,
        thinking,
        sourceUpdatedAt,
        promptVersion,
        status,
    )

    fun markStaleIfChanged(currentUpdatedAt: String?, currentPromptVersion: String): TriageAssessmentCells {
        val sourceChanged = sourceUpdatedAt != currentUpdatedAt.orEmpty()
        val promptChanged = promptVersion != currentPromptVersion
        return if (sourceChanged || promptChanged) copy(status = AssessmentStatus.Stale.name) else this
    }

    fun isError(): Boolean = status == AssessmentStatus.Error.name

    companion object {
        fun error(): TriageAssessmentCells = TriageAssessmentCells(
            difficulty = "",
            prNeeded = "",
            prReason = "",
            rationale = "",
            confidence = "",
            model = "",
            thinking = "",
            sourceUpdatedAt = "",
            promptVersion = "",
            status = AssessmentStatus.Error.name,
        )

        fun from(values: List<String>): TriageAssessmentCells? {
            require(values.size == ASSESSMENT_COLUMN_COUNT) { "Unexpected assessment column count" }
            if (values.all(String::isBlank)) return null
            return TriageAssessmentCells(
                difficulty = values[0],
                prNeeded = values[1],
                prReason = values[2],
                rationale = values[3],
                confidence = values[4],
                model = values[5],
                thinking = values[6],
                sourceUpdatedAt = values[7],
                promptVersion = values[8],
                status = values[9],
            )
        }
    }
}

internal fun IssueAssessment.toCells(): TriageAssessmentCells = TriageAssessmentCells(
    difficulty = difficulty.toString(),
    prNeeded = prNeeded.name,
    prReason = prReason,
    rationale = rationale,
    confidence = confidence.name,
    model = model,
    thinking = thinking,
    sourceUpdatedAt = sourceUpdatedAt.orEmpty(),
    promptVersion = promptVersion,
    status = status.name,
)

private const val ASSESSMENT_COLUMN_COUNT = 10
