package com.github.karlsabo.devlake.tools.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.tools.ProjectSummaryHolder

@Composable
fun ProjectSummaryList(
    summaries: List<ProjectSummaryHolder>,
    onMessageChange: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        itemsIndexed(
            items = summaries,
            key = { _, summary -> summary.projectSummary.project.id }
        ) { index, summaryHolder ->
            ProjectSummaryItem(
                message = summaryHolder.message,
                onMessageChange = { onMessageChange(index, it) },
                onDelete = { onDelete(index) }
            )
        }
    }
}

@Composable
private fun ProjectSummaryItem(
    message: String,
    onMessageChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = message,
            onValueChange = onMessageChange,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onDelete,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text("Delete")
        }
    }
}
