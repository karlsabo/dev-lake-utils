package com.github.karlsabo.devlake.triage.spreadsheet

import com.github.karlsabo.devlake.triage.TriageAssessmentCells
import com.github.karlsabo.devlake.triage.TriageColumn
import com.github.karlsabo.devlake.triage.TriageInventory
import com.github.karlsabo.devlake.triage.TriageRefresh
import com.github.karlsabo.devlake.triage.TriageRow
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import org.odftoolkit.odfdom.doc.table.OdfTable
import org.w3c.dom.Element
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OdsTriageWorkbookTest {
    @Test
    fun `writes an editable sorted Ranking sheet with ODS usability metadata`() {
        val output = Files.createTempDirectory("ods-ranking-test").resolve("ranking.ods")

        OdsTriageWorkbook().write(inventory(), output)

        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            assertEquals(listOf("TST-3", "TST-20", "TST-10", "TST-2"), ranking.ticketIds())

            val difficulty = ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, 1)
            assertEquals("float", difficulty.valueType)
            assertEquals(2.0, difficulty.doubleValue)
            assertEquals("2", difficulty.stringValue)

            val ticket = ranking.getCellByPosition(TriageColumn.TICKET_ID.ordinal, 1)
            val link = ticket.odfElement.getElementsByTagNameNS(TEXT_NAMESPACE, "a").item(0) as Element
            assertEquals("https://linear/TST-3", link.getAttributeNS(XLINK_NAMESPACE, "href"))
            assertEquals("TST-3", link.textContent)
        }

        val content = output.zipEntry("content.xml")
        assertTrue(content.contains("table:name=\"RankingFilter\""))
        assertTrue(content.contains("table:target-range-address=\"\$Ranking.\$A\$1:.\$Q\$5\""))
        assertTrue(content.contains("table:display-filter-buttons=\"true\""))
        assertTrue(content.contains("fo:wrap-option=\"wrap\""))
        assertTrue(content.contains("style:column-width=\"3.1496in\""))

        val settings = output.zipEntry("settings.xml")
        assertTrue(settings.contains("config:name=\"Ranking\""))
        assertTrue(settings.contains(configItem("HorizontalSplitMode", "short", "2")))
        assertTrue(settings.contains(configItem("HorizontalSplitPosition", "int", "1")))
        assertTrue(settings.contains(configItem("PositionBottom", "int", "1")))
    }

    @Test
    fun `writes and reloads active rows with pending assessments`() {
        val output = Files.createTempDirectory("ods-pending-test").resolve("ranking.ods")
        val workbook = OdsTriageWorkbook()
        val pendingInventory = inventory().copy(
            rows = inventory().rows + row("id-21", "TST-21", "").copy(assessment = null),
        )

        workbook.write(pendingInventory, output)
        val merged = workbook.mergeExisting(TriageRefresh(pendingInventory, emptyMap()), output)

        val pending = merged.rows.single { it.linearId == "id-21" }
        assertEquals(null, pending.assessment)
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowIndex = (1 until ranking.rowCount).single { index ->
                ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, index).stringValue == "id-21"
            }
            TriageColumn.entries.drop(TriageColumn.DIFFICULTY.ordinal).forEach { column ->
                assertEquals("", ranking.getCellByPosition(column.ordinal, rowIndex).stringValue)
            }
        }
    }

    @Test
    fun `refresh rejects a workbook created for a different scope`() {
        val output = Files.createTempDirectory("ods-scope-mismatch-test").resolve("ranking.ods")
        val workbook = OdsTriageWorkbook()
        val original = inventory()
        workbook.write(original, output)
        val differentScope = original.copy(team = "OTHER")

        val failure = assertFailsWith<IllegalArgumentException> {
            workbook.mergeExisting(TriageRefresh(differentScope, emptyMap()), output)
        }

        assertTrue(failure.message.orEmpty().contains("does not match requested scope"))
    }

    @Test
    fun `refresh ignores trailing blank rows added by a spreadsheet editor`() {
        val output = Files.createTempDirectory("ods-trailing-rows-test").resolve("ranking.ods")
        val workbook = OdsTriageWorkbook()
        val original = inventory()
        workbook.write(original, output)
        appendBlankRows(output, 3)

        val merged = workbook.mergeExisting(TriageRefresh(original, emptyMap()), output)

        assertEquals(original.rows.map(TriageRow::linearId), merged.rows.map(TriageRow::linearId))
    }

    @Test
    fun `refresh still rejects a blank row within Ranking data`() {
        val output = Files.createTempDirectory("ods-blank-gap-test").resolve("ranking.ods")
        val workbook = OdsTriageWorkbook()
        val original = inventory()
        workbook.write(original, output)
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            ranking.insertRowsBefore(2, 1)
            document.save(output.toFile())
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            workbook.mergeExisting(TriageRefresh(original, emptyMap()), output)
        }

        assertEquals("Ranking row 3 has no immutable Linear ID", failure.message)
    }

    @Test
    fun `refresh round trip retains edited cells and reapplies Ranking usability`() {
        val output = Files.createTempDirectory("ods-ranking-round-trip-test").resolve("ranking.ods")
        val workbook = OdsTriageWorkbook()
        val original = inventory()
        workbook.write(original, output)
        editDifficulty(output, "id-3", "human estimate")

        val merged = workbook.mergeExisting(TriageRefresh(original, emptyMap()), output)
        workbook.write(merged, output)

        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            assertEquals(listOf("TST-20", "TST-10", "TST-2", "TST-3"), ranking.ticketIds())
            val edited = ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, 4)
            assertEquals("string", edited.valueType)
            assertEquals("human estimate", edited.stringValue)
            assertEquals(
                "https://linear/TST-3",
                (
                    ranking.getCellByPosition(TriageColumn.TICKET_ID.ordinal, 4).odfElement
                        .getElementsByTagNameNS(TEXT_NAMESPACE, "a").item(0) as Element
                    )
                    .getAttributeNS(XLINK_NAMESPACE, "href"),
            )
        }
        assertTrue(output.zipEntry("content.xml").contains("table:display-filter-buttons=\"true\""))
        assertTrue(output.zipEntry("settings.xml").contains(configItem("HorizontalSplitPosition", "int", "1")))
    }

    private fun inventory(): TriageInventory = TriageInventory(
        generatedAt = "2026-04-02T12:00:00Z",
        team = "TST",
        project = "Test Project",
        label = "test-label",
        assessmentModel = "test-model",
        assessmentThinking = "medium",
        assessmentPromptVersion = "test-v1",
        rows = listOf(
            row("id-10", "TST-10", "8"),
            row("id-2", "TST-2", "", status = "Error"),
            row("id-20", "TST-20", "2"),
            row("id-3", "TST-3", "2"),
        ),
    )

    private fun row(
        linearId: String,
        identifier: String,
        difficulty: String,
        status: String = "Assessed",
    ): TriageRow = TriageRow(
        linearId = linearId,
        identifier = identifier,
        title = "A title that can wrap for $identifier",
        description = null,
        url = "https://linear/$identifier",
        state = "Backlog",
        ktloSource = "project + label",
        updatedAt = "2026-04-02T00:00:00Z",
        assessment = TriageAssessmentCells(
            difficulty = difficulty,
            prNeeded = if (status == "Error") "" else "Yes",
            prReason = if (status == "Error") "" else "A reason that can wrap",
            rationale = if (status == "Error") "" else "src/example.kt:12 has detailed evidence that can wrap",
            confidence = if (status == "Error") "" else "High",
            model = if (status == "Error") "" else "test-model",
            thinking = if (status == "Error") "" else "medium",
            sourceUpdatedAt = if (status == "Error") "" else "2026-04-02T00:00:00Z",
            promptVersion = if (status == "Error") "" else "test-v1",
            status = status,
        ),
    )

    private fun appendBlankRows(output: java.nio.file.Path, count: Int) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            ranking.appendRows(count)
            document.save(output.toFile())
        }
    }

    private fun editDifficulty(
        output: java.nio.file.Path,
        linearId: String,
        value: String,
    ) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowIndex = (1 until ranking.rowCount).single { index ->
                ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, index).stringValue == linearId
            }
            ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, rowIndex).setStringValue(value)
            document.save(output.toFile())
        }
    }

    private fun OdfTable.ticketIds(): List<String> = (1 until rowCount).map { rowIndex ->
        getCellByPosition(TriageColumn.TICKET_ID.ordinal, rowIndex).stringValue
    }

    private fun java.nio.file.Path.zipEntry(name: String): String = ZipFile(toFile()).use { archive ->
        archive.getInputStream(archive.getEntry(name)).bufferedReader().readText()
    }

    private fun configItem(
        name: String,
        type: String,
        value: String,
    ): String = "<config:config-item config:name=\"$name\" config:type=\"$type\">$value</config:config-item>"

    private companion object {
        const val TEXT_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
    }
}
