package com.github.karlsabo.devlake.triage.spreadsheet

import com.github.karlsabo.devlake.triage.ArchiveReason
import com.github.karlsabo.devlake.triage.ArchivedTriageRow
import com.github.karlsabo.devlake.triage.TriageAssessmentCells
import com.github.karlsabo.devlake.triage.TriageColumn
import com.github.karlsabo.devlake.triage.TriageRow
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import org.odftoolkit.odfdom.doc.table.OdfTable
import java.nio.file.Path

internal data class ExistingWorkbook(
    val scope: WorkbookScope,
    val activeRows: List<TriageRow>,
    val archivedRows: List<ArchivedTriageRow>,
)

internal fun readExistingWorkbook(
    output: Path,
    requireArchivedSheet: Boolean = false,
): ExistingWorkbook = loadDocument(output).use { document ->
    val scope = readScope(requireTable(document, "Summary"))
    val ranking = requireTable(document, "Ranking")
    validateHeaders(ranking, TriageColumn.entries.map(TriageColumn::header), "Ranking")
    val activeRows = ranking.readRows("Ranking", TriageColumn.entries.size)
    val archivedTable = findTable(document, "Archived")
    require(!requireArchivedSheet || archivedTable != null) {
        "Workbook must contain exactly one Archived sheet"
    }
    val archivedRows = archivedTable?.let(::readArchivedRows).orEmpty()
    requireUniqueIds(activeRows, archivedRows)
    ExistingWorkbook(scope, activeRows, archivedRows)
}

private fun readArchivedRows(table: OdfTable): List<ArchivedTriageRow> {
    val headers = TriageColumn.entries.map(TriageColumn::header) + ARCHIVE_HEADERS
    validateHeaders(table, headers, "Archived")
    return table.readRows("Archived", headers.size).mapIndexed { index, row ->
        val rowIndex = index + 1
        val reason = ArchiveReason.from(table.stringValue(TriageColumn.entries.size, rowIndex))
        val archivedAt = table.stringValue(TriageColumn.entries.size + 1, rowIndex)
        require(archivedAt.isNotBlank()) { "Archived row ${rowIndex + 1} has no archive timestamp" }
        ArchivedTriageRow(row, reason, archivedAt)
    }
}

private fun OdfTable.readRows(
    sheetName: String,
    columnCount: Int,
): List<TriageRow> = (1 until populatedRowCount(columnCount)).map { rowIndex ->
    val linearId = stringValue(TriageColumn.LINEAR_ID.ordinal, rowIndex)
    require(linearId.isNotBlank()) { "$sheetName row ${rowIndex + 1} has no immutable Linear ID" }
    TriageRow(
        linearId = linearId,
        identifier = stringValue(TriageColumn.TICKET_ID.ordinal, rowIndex),
        title = stringValue(TriageColumn.TITLE.ordinal, rowIndex),
        description = null,
        url = stringValue(TriageColumn.URL.ordinal, rowIndex).ifBlank { null },
        state = stringValue(TriageColumn.STATE.ordinal, rowIndex).ifBlank { null },
        ktloSource = stringValue(TriageColumn.KTLO_SOURCE.ordinal, rowIndex),
        updatedAt = stringValue(TriageColumn.UPDATED_AT.ordinal, rowIndex).ifBlank { null },
        assessment = readAssessment(rowIndex),
    )
}

private fun OdfTable.readAssessment(rowIndex: Int): TriageAssessmentCells? {
    val values = TriageColumn.entries
        .filter { column -> column.ordinal >= TriageColumn.DIFFICULTY.ordinal }
        .map { column -> stringValue(column.ordinal, rowIndex) }
    return TriageAssessmentCells.from(values)
}

private fun requireUniqueIds(activeRows: List<TriageRow>, archivedRows: List<ArchivedTriageRow>) {
    val ids = activeRows.map(TriageRow::linearId) + archivedRows.map { it.row.linearId }
    require(ids.size == ids.distinct().size) { "Ranking and Archived contain duplicate immutable Linear IDs" }
}

private fun validateHeaders(
    table: OdfTable,
    expected: List<String>,
    sheetName: String,
) {
    val actual = expected.indices.map { columnIndex -> table.stringValue(columnIndex, 0) }
    require(actual == expected) { "$sheetName sheet columns do not match the supported schema" }
}

private fun OdfTable.stringValue(columnIndex: Int, rowIndex: Int): String {
    val cell = getCellByPosition(columnIndex, rowIndex)
    return cell.stringValue.orEmpty()
}

private fun requireTable(
    document: OdfSpreadsheetDocument,
    name: String,
): OdfTable = requireNotNull(findTable(document, name)) {
    "Workbook must contain exactly one $name sheet"
}

private fun findTable(document: OdfSpreadsheetDocument, name: String): OdfTable? {
    val matchingTables = document.spreadsheetTables.filter { table -> table.tableName == name }
    require(matchingTables.size <= 1) { "Workbook must contain exactly one $name sheet" }
    return matchingTables.singleOrNull()
}

private fun loadDocument(output: Path): OdfSpreadsheetDocument = OdfSpreadsheetDocument.loadDocument(output.toFile())

internal val ARCHIVE_HEADERS = listOf("Archive reason", "Archived at")
