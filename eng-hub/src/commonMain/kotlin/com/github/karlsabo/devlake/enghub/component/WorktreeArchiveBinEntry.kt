package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class WorktreeArchiveBinEntry(
    val repository: String,
    val branch: String,
    val remainingSeconds: Long,
    val worktreePath: String,
)

@Composable
internal fun WorktreeArchiveBin(
    entries: List<WorktreeArchiveBinEntry>,
    modifier: Modifier = Modifier,
    onUndo: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = "Recycle bin (${entries.size})" },
        ) {
            Text(text = "♻", style = MaterialTheme.typography.button)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Recycle bin", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.size(8.dp))
                if (entries.isEmpty()) {
                    Text("No worktrees queued for archive")
                } else {
                    entries.forEach { entry ->
                        key(entry.worktreePath) {
                            WorktreeArchiveBinEntryRow(entry, onUndo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorktreeArchiveBinEntryRow(
    entry: WorktreeArchiveBinEntry,
    onUndo: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(entry.branch, style = MaterialTheme.typography.body1)
        Text(entry.repository, style = MaterialTheme.typography.caption)
        Text("${entry.remainingSeconds} seconds remaining", style = MaterialTheme.typography.caption)
        TextButton(onClick = { onUndo(entry.worktreePath) }) {
            Text("Undo")
        }
    }
}
