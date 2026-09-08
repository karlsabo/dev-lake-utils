package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.ASSESSMENT_PROMPT_VERSION
import com.github.karlsabo.linear.LinearTriageIssue

internal data class TriageInventory(
    val generatedAt: String,
    val team: String,
    val project: String?,
    val label: String?,
    val assessmentModel: String,
    val assessmentThinking: String,
    val assessmentPromptVersion: String,
    val rows: List<TriageRow>,
    val archivedRows: List<ArchivedTriageRow> = emptyList(),
)

internal data class TriageRow(
    val linearId: String,
    val identifier: String,
    val title: String,
    val description: String?,
    val url: String?,
    val state: String?,
    val ktloSource: String,
    val updatedAt: String?,
    val assessment: TriageAssessmentCells? = null,
)

internal data class ArchivedTriageRow(
    val row: TriageRow,
    val reason: ArchiveReason,
    val archivedAt: String,
)

internal enum class ArchiveReason(
    val value: String,
) {
    COMPLETED("Completed"),
    CANCELED("Canceled"),
    ARCHIVED("Archived in Linear"),
    NO_LONGER_MATCHES_SCOPE("No longer matches scope"),
    ;

    companion object {
        fun from(value: String): ArchiveReason = entries.singleOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown archive reason: $value")
    }
}

internal data class TriageRefresh(
    val inventory: TriageInventory,
    val inactiveRows: Map<String, InactiveTriageRow>,
)

internal data class InactiveTriageRow(
    val row: TriageRow,
    val reason: ArchiveReason,
)

internal fun buildRefresh(
    issues: List<LinearTriageIssue>,
    arguments: TriageArguments,
    generatedAt: String,
): TriageRefresh {
    val matchingIssues = issues.filter { issue -> issue.matches(arguments) }
    val activeRows = matchingIssues.filterNot(LinearTriageIssue::isTerminal).map { it.toTriageRow(arguments) }
    val inactiveRows = matchingIssues.filter(LinearTriageIssue::isTerminal).associate { issue ->
        issue.id to InactiveTriageRow(issue.toTriageRow(arguments), issue.archiveReason())
    }
    return TriageRefresh(
        inventory = TriageInventory(
            generatedAt = generatedAt,
            team = arguments.team,
            project = arguments.project,
            label = arguments.label,
            assessmentModel = arguments.model,
            assessmentThinking = arguments.thinking,
            assessmentPromptVersion = ASSESSMENT_PROMPT_VERSION,
            rows = activeRows.sortedBy(TriageRow::identifier),
        ),
        inactiveRows = inactiveRows,
    )
}

private fun LinearTriageIssue.matches(arguments: TriageArguments): Boolean {
    val matchesProject = arguments.project != null && projectName == arguments.project
    val matchesLabel = arguments.label != null && arguments.label in labelNames
    return matchesProject || matchesLabel
}

private fun LinearTriageIssue.isTerminal(): Boolean = completedAt != null ||
    canceledAt != null ||
    archivedAt != null ||
    stateType?.lowercase() in setOf("completed", "canceled")

private fun LinearTriageIssue.archiveReason(): ArchiveReason = when {
    canceledAt != null || stateType.equals("canceled", ignoreCase = true) -> ArchiveReason.CANCELED
    completedAt != null || stateType.equals("completed", ignoreCase = true) -> ArchiveReason.COMPLETED
    archivedAt != null -> ArchiveReason.ARCHIVED
    else -> error("Issue $identifier is not terminal")
}

private fun LinearTriageIssue.toTriageRow(arguments: TriageArguments): TriageRow {
    val inProject = arguments.project != null && projectName == arguments.project
    val hasLabel = arguments.label != null && arguments.label in labelNames
    require(inProject || hasLabel) { "Linear issue $identifier does not match the requested scope" }
    return TriageRow(
        linearId = id,
        identifier = identifier,
        title = title,
        description = description,
        url = url,
        state = stateName,
        ktloSource = ktloSource(inProject, hasLabel, arguments),
        updatedAt = updatedAt?.toString(),
    )
}

private fun ktloSource(
    inProject: Boolean,
    hasLabel: Boolean,
    arguments: TriageArguments,
): String = when {
    inProject && hasLabel -> "project + label"
    inProject -> "${arguments.project} project"
    else -> "${arguments.label} label"
}
