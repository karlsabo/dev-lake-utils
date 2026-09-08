package com.github.karlsabo.devlake.triage.spreadsheet

import org.odftoolkit.odfdom.doc.table.OdfTable

internal data class WorkbookScope(
    val team: String,
    val project: String?,
    val label: String?,
)

internal fun readScope(summary: OdfTable): WorkbookScope {
    val team = summary.metadataValue("Team", TEAM_ROW_INDEX)
    require(team.isNotBlank()) { "Summary sheet has no Team value" }
    return WorkbookScope(
        team = team,
        project = summary.metadataValue("Project", PROJECT_ROW_INDEX).ifBlank { null },
        label = summary.metadataValue("Label", LABEL_ROW_INDEX).ifBlank { null },
    )
}

private fun OdfTable.metadataValue(label: String, rowIndex: Int): String {
    require(getCellByPosition(LABEL_COLUMN_INDEX, rowIndex).stringValue.orEmpty() == label) {
        "Summary sheet does not contain $label metadata"
    }
    return getCellByPosition(VALUE_COLUMN_INDEX, rowIndex).stringValue.orEmpty()
}

private const val LABEL_COLUMN_INDEX = 0
private const val VALUE_COLUMN_INDEX = 1
private const val TEAM_ROW_INDEX = 2
private const val PROJECT_ROW_INDEX = 3
private const val LABEL_ROW_INDEX = 4
