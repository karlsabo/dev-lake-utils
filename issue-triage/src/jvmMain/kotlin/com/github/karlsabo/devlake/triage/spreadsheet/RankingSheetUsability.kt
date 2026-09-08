package com.github.karlsabo.devlake.triage.spreadsheet

import com.github.karlsabo.devlake.triage.TriageColumn
import com.github.karlsabo.devlake.triage.TriageRow
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import org.odftoolkit.odfdom.doc.table.OdfTable
import org.odftoolkit.odfdom.dom.element.style.StyleTableCellPropertiesElement
import org.odftoolkit.odfdom.dom.style.OdfStyleFamily
import org.w3c.dom.Element

internal const val RANKING_SHEET = "Ranking"

internal val RANKING_ORDER = compareBy<TriageRow>(
    { row ->
        row.assessment?.difficulty?.toIntOrNull()
            ?.takeIf { difficulty ->
                row.assessment.status != ERROR_STATUS && difficulty in MIN_DIFFICULTY..MAX_DIFFICULTY
            }
            ?: Int.MAX_VALUE
    },
    { row -> row.identifier.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE },
    TriageRow::identifier,
    TriageRow::linearId,
)

internal fun configureRankingView(document: OdfSpreadsheetDocument, table: OdfTable) {
    RANKING_COLUMN_WIDTHS.forEachIndexed { index, width -> table.getColumnByIndex(index).setWidth(width) }
    table.getColumnByIndex(TriageColumn.LINEAR_ID.ordinal).odfElement.setTableVisibilityAttribute("collapse")
    addRankingFilter(document, table)
    freezeRankingHeader(document)
}

internal fun applyRankingCellFormatting(table: OdfTable, rows: List<TriageRow>) {
    val headerStyle = createCellStyle(table) { properties ->
        properties.setFoBackgroundColorAttribute(HEADER_BACKGROUND)
        properties.setFoWrapOptionAttribute("wrap")
    }
    val wrappedStyle = createCellStyle(table) { properties ->
        properties.setFoWrapOptionAttribute("wrap")
        properties.setStyleVerticalAlignAttribute("top")
    }
    TriageColumn.entries.indices.forEach { columnIndex ->
        table.getCellByPosition(columnIndex, HEADER_ROW).odfElement.setTableStyleNameAttribute(headerStyle)
    }
    rows.forEachIndexed { index, row -> applyRowFormatting(table, index + FIRST_DATA_ROW, row, wrappedStyle) }
}

private fun applyRowFormatting(
    table: OdfTable,
    rowIndex: Int,
    row: TriageRow,
    wrappedStyle: String,
) {
    listOf(TriageColumn.TITLE, TriageColumn.PR_REASON, TriageColumn.RATIONALE).forEach { column ->
        table.getCellByPosition(column.ordinal, rowIndex).odfElement.setTableStyleNameAttribute(wrappedStyle)
    }
    row.url?.takeIf(String::isNotBlank)?.let { url ->
        addHyperlink(table, TriageColumn.TICKET_ID.ordinal, rowIndex, row.identifier, url)
    }
    row.assessment?.difficulty?.toIntOrNull()?.takeIf { it in MIN_DIFFICULTY..MAX_DIFFICULTY }?.let { difficulty ->
        table.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, rowIndex)
            .setDoubleValue(difficulty.toDouble())
            .setDisplayText(difficulty.toString())
    }
}

private fun createCellStyle(
    table: OdfTable,
    configure: (StyleTableCellPropertiesElement) -> Unit,
): String {
    val style = table.odfElement.getOrCreateAutomaticStyles().newStyle(OdfStyleFamily.TableCell)
    configure(style.newStyleTableCellPropertiesElement())
    return style.styleNameAttribute
}

private fun addHyperlink(
    table: OdfTable,
    columnIndex: Int,
    rowIndex: Int,
    label: String,
    url: String,
) {
    val cell = table.getCellByPosition(columnIndex, rowIndex)
    val paragraph = cell.odfElement.getElementsByTagNameNS(TEXT_NAMESPACE, "p").item(0)
    paragraph.textContent = ""
    val link = cell.odfElement.ownerDocument.createElementNS(TEXT_NAMESPACE, "text:a")
    link.setAttributeNS(XLINK_NAMESPACE, "xlink:href", url)
    link.setAttributeNS(XLINK_NAMESPACE, "xlink:type", "simple")
    link.textContent = label
    paragraph.appendChild(link)
}

private fun addRankingFilter(document: OdfSpreadsheetDocument, table: OdfTable) {
    val lastColumn = spreadsheetColumnName(TriageColumn.entries.lastIndex)
    val databaseRange = document.contentRoot
        .newTableDatabaseRangesElement()
        .newTableDatabaseRangeElement("\$$RANKING_SHEET.\$A\$1:.\$$lastColumn\$${table.rowCount}")
    databaseRange.setTableNameAttribute(RANKING_FILTER_NAME)
    databaseRange.setTableDisplayFilterButtonsAttribute(true)
}

private fun freezeRankingHeader(document: OdfSpreadsheetDocument) {
    val settings = document.settingsDom
    val tableView = settings.getElementsByTagNameNS(CONFIG_NAMESPACE, "config-item-map-entry")
        .asElements()
        .single { element -> element.getAttributeNS(CONFIG_NAMESPACE, "name") == TEMPLATE_SHEET }
    tableView.setAttributeNS(CONFIG_NAMESPACE, "config:name", RANKING_SHEET)
    setConfigItem(tableView, "HorizontalSplitMode", FROZEN_SPLIT_MODE)
    setConfigItem(tableView, "HorizontalSplitPosition", FIRST_DATA_ROW.toString())
    setConfigItem(tableView, "PositionBottom", FIRST_DATA_ROW.toString())

    settings.getElementsByTagNameNS(CONFIG_NAMESPACE, "config-item")
        .asElements()
        .single { element -> element.getAttributeNS(CONFIG_NAMESPACE, "name") == "ActiveTable" }
        .textContent = RANKING_SHEET
}

private fun setConfigItem(
    parent: Element,
    name: String,
    value: String,
) {
    parent.getElementsByTagNameNS(CONFIG_NAMESPACE, "config-item")
        .asElements()
        .single { element -> element.getAttributeNS(CONFIG_NAMESPACE, "name") == name }
        .textContent = value
}

private fun org.w3c.dom.NodeList.asElements(): List<Element> = (0 until length).map { index -> item(index) as Element }

private fun spreadsheetColumnName(index: Int): String {
    var remaining = index
    return buildString {
        do {
            insert(0, 'A' + remaining % ALPHABET_SIZE)
            remaining = remaining / ALPHABET_SIZE - 1
        } while (remaining >= 0)
    }
}

private val RANKING_COLUMN_WIDTHS = listOf(
    STANDARD_WIDTH,
    TICKET_WIDTH,
    TITLE_WIDTH,
    URL_WIDTH,
    STATE_WIDTH,
    STANDARD_WIDTH,
    UPDATED_AT_WIDTH,
    DIFFICULTY_WIDTH,
    PR_NEEDED_WIDTH,
    LONG_TEXT_WIDTH,
    RATIONALE_WIDTH,
    PR_NEEDED_WIDTH,
    MODEL_WIDTH,
    STATE_WIDTH,
    TIMESTAMP_WIDTH,
    PROMPT_WIDTH,
    STATUS_WIDTH,
)

private const val HEADER_ROW = 0
private const val FIRST_DATA_ROW = 1
private const val MIN_DIFFICULTY = 1
private const val MAX_DIFFICULTY = 10
private const val ALPHABET_SIZE = 26
private const val STANDARD_WIDTH = 35L
private const val TICKET_WIDTH = 24L
private const val TITLE_WIDTH = 65L
private const val URL_WIDTH = 55L
private const val STATE_WIDTH = 28L
private const val UPDATED_AT_WIDTH = 42L
private const val DIFFICULTY_WIDTH = 22L
private const val PR_NEEDED_WIDTH = 25L
private const val LONG_TEXT_WIDTH = 50L
private const val RATIONALE_WIDTH = 80L
private const val MODEL_WIDTH = 45L
private const val TIMESTAMP_WIDTH = 48L
private const val PROMPT_WIDTH = 38L
private const val STATUS_WIDTH = 32L
private const val RANKING_FILTER_NAME = "RankingFilter"
private const val HEADER_BACKGROUND = "#D9EAF7"
private const val TEMPLATE_SHEET = "Sheet1"
private const val FROZEN_SPLIT_MODE = "2"
private const val ERROR_STATUS = "Error"
private const val TEXT_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
private const val CONFIG_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:config:1.0"
