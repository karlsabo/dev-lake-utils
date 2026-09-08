package com.github.karlsabo.devlake.triage.spreadsheet

import org.odftoolkit.odfdom.doc.table.OdfTable
import org.odftoolkit.odfdom.doc.table.OdfTableRow

internal fun OdfTable.populatedRowCount(columnCount: Int): Int {
    var logicalRowCount = 0
    var populatedRowCount = 0
    rowElementList.forEach { element ->
        logicalRowCount += element.tableNumberRowsRepeatedAttribute
        val row = OdfTableRow.getInstance(element)
        if ((0 until columnCount).any { columnIndex -> row.getCellByIndex(columnIndex).stringValue.isNotBlank() }) {
            populatedRowCount = logicalRowCount
        }
    }
    return populatedRowCount
}
