package com.github.karlsabo.devlake.triage.spreadsheet

import com.github.karlsabo.devlake.triage.ArchiveReason
import com.github.karlsabo.devlake.triage.ArchivedTriageRow
import com.github.karlsabo.devlake.triage.TriageColumn
import com.github.karlsabo.devlake.triage.TriageInventory
import com.github.karlsabo.devlake.triage.TriageRefresh
import com.github.karlsabo.devlake.triage.TriageRow
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import org.odftoolkit.odfdom.doc.table.OdfTable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal interface TriageWorkbook {
    fun mergeExisting(refresh: TriageRefresh, output: Path): TriageInventory

    fun write(inventory: TriageInventory, output: Path)
}

internal class OdsTriageWorkbook : TriageWorkbook {
    override fun mergeExisting(refresh: TriageRefresh, output: Path): TriageInventory {
        val inventory = refresh.inventory
        if (!Files.exists(output)) return inventory
        val existing = readExistingWorkbook(output)
        require(existing.scope == inventory.scope()) {
            "Workbook scope ${existing.scope} does not match requested scope ${inventory.scope()}"
        }
        val existingActive = existing.activeRows.associateBy(TriageRow::linearId)
        val existingArchived = existing.archivedRows.associateBy { it.row.linearId }
        val activeRows = inventory.rows.map { row ->
            val priorAssessment = existingActive[row.linearId]?.assessment
                ?: existingArchived[row.linearId]?.row?.assessment
            row.copy(assessment = priorAssessment)
        }
        val activeIds = activeRows.mapTo(mutableSetOf(), TriageRow::linearId)
        val retainedArchived = existing.archivedRows.filterNot { it.row.linearId in activeIds }
        val newlyArchived = existing.activeRows.filterNot { it.linearId in activeIds }.map { priorRow ->
            val inactive = refresh.inactiveRows[priorRow.linearId]
            ArchivedTriageRow(
                row = (inactive?.row ?: priorRow).copy(assessment = priorRow.assessment),
                reason = inactive?.reason ?: ArchiveReason.NO_LONGER_MATCHES_SCOPE,
                archivedAt = inventory.generatedAt,
            )
        }
        return inventory.copy(
            rows = activeRows,
            archivedRows = (retainedArchived + newlyArchived).sortedBy { it.row.identifier },
        )
    }

    override fun write(inventory: TriageInventory, output: Path) {
        require(output.fileName.toString().endsWith(".ods", ignoreCase = true)) {
            "Output path must end in .ods"
        }
        val target = output.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "Output path must have a parent directory" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${target.fileName}-", ".ods")
        try {
            writeDocument(inventory, temporary)
            validateDocument(temporary)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun TriageInventory.scope(): WorkbookScope = WorkbookScope(team, project, label)

private fun validateDocument(output: Path) {
    readExistingWorkbook(output, requireArchivedSheet = true)
}

private fun writeDocument(inventory: TriageInventory, output: Path) {
    val document = OdfSpreadsheetDocument.newSpreadsheetDocument()
    try {
        val originalTables = document.spreadsheetTables.toList()
        createSummaryTable(document, inventory)
        val ranking = createRankingTable(document, inventory.rows)
        createArchivedTable(document, inventory.archivedRows)
        originalTables.forEach(OdfTable::remove)
        configureRankingView(document, ranking)
        document.save(output.toFile())
    } finally {
        document.close()
    }
}

private fun createSummaryTable(document: OdfSpreadsheetDocument, inventory: TriageInventory) {
    val values = listOf(
        listOf("Generation metadata", "Value"),
        listOf("Generated at", inventory.generatedAt),
        listOf("Team", inventory.team),
        listOf("Project", inventory.project.orEmpty()),
        listOf("Label", inventory.label.orEmpty()),
        listOf("Assessment model", inventory.assessmentModel),
        listOf("Assessment thinking", inventory.assessmentThinking),
        listOf("Assessment prompt version", inventory.assessmentPromptVersion),
        listOf("Active issue count", inventory.rows.size.toString()),
        listOf("Archived issue count", inventory.archivedRows.size.toString()),
    )
    createTable(document, "Summary", values)
}

private fun createRankingTable(document: OdfSpreadsheetDocument, rows: List<TriageRow>): OdfTable {
    val sortedRows = rows.sortedWith(RANKING_ORDER)
    val values = buildList {
        add(TriageColumn.entries.map(TriageColumn::header))
        sortedRows.forEach { row -> add(row.values()) }
    }
    return createTable(document, RANKING_SHEET, values).also { table ->
        applyRankingCellFormatting(table, sortedRows)
    }
}

private fun createArchivedTable(document: OdfSpreadsheetDocument, rows: List<ArchivedTriageRow>) {
    val values = buildList {
        add(TriageColumn.entries.map(TriageColumn::header) + ARCHIVE_HEADERS)
        rows.forEach { archived ->
            add(archived.row.values() + listOf(archived.reason.value, archived.archivedAt))
        }
    }
    val table = createTable(document, "Archived", values)
    hideLinearId(table)
}

private fun hideLinearId(table: OdfTable) {
    table.getColumnByIndex(TriageColumn.LINEAR_ID.ordinal).odfElement.setTableVisibilityAttribute("collapse")
}

private fun TriageRow.values(): List<String> {
    val assessmentValues = assessment?.values() ?: List(ASSESSMENT_COLUMN_COUNT) { "" }
    return listOf(
        linearId,
        identifier,
        title,
        url.orEmpty(),
        state.orEmpty(),
        ktloSource,
        updatedAt.orEmpty(),
    ) + assessmentValues
}

private fun createTable(
    document: OdfSpreadsheetDocument,
    name: String,
    values: List<List<String>>,
): OdfTable {
    val columnCount = values.maxOf(List<String>::size)
    val table = OdfTable.newTable(document, values.size, columnCount).setTableName(name)
    values.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, value ->
            table.getCellByPosition(columnIndex, rowIndex).setStringValue(value)
        }
    }
    return table
}

private const val ASSESSMENT_COLUMN_COUNT = 10
