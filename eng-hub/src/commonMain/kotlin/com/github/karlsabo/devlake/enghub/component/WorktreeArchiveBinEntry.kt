package com.github.karlsabo.devlake.enghub.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BIN_PULSE_DURATION_MS = 160
private const val BIN_PULSE_SCALE = 1.25f

internal data class WorktreeArchiveBinEntry(
    val repository: String,
    val branch: String,
    val remainingSeconds: Long,
    val worktreePath: String,
    val queueId: String = "",
    val isNewlyQueued: Boolean = false,
    val isRemoving: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val needsForceConfirmation: Boolean = false,
)

internal data class WorktreeArchiveBinActions(
    val onUndo: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onRequestForceConfirmation: (String) -> Unit = {},
)

@Composable
internal fun WorktreeArchiveBin(
    entries: List<WorktreeArchiveBinEntry>,
    modifier: Modifier = Modifier,
    actions: WorktreeArchiveBinActions = WorktreeArchiveBinActions(),
) {
    var expanded by remember { mutableStateOf(false) }
    val pulseScale = remember { Animatable(1f) }
    val pulseMutex = remember { Mutex() }
    val binScope = rememberCoroutineScope()
    entries.filter { it.isNewlyQueued }.forEach { entry ->
        key(entry.queueId) {
            LaunchedEffect(entry.queueId) {
                // The bin owns the shared scale; removing one entry must not cancel its return to rest.
                binScope.launch {
                    pulseMutex.withLock {
                        pulseScale.animateTo(BIN_PULSE_SCALE, tween(BIN_PULSE_DURATION_MS))
                        pulseScale.animateTo(1f, tween(BIN_PULSE_DURATION_MS))
                    }
                }
            }
        }
    }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer {
                    scaleX = pulseScale.value
                    scaleY = pulseScale.value
                }
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
                            WorktreeArchiveBinEntryRow(entry, actions)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorktreeArchiveBinEntryRow(
    entry: WorktreeArchiveBinEntry,
    actions: WorktreeArchiveBinActions,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(entry.branch, style = MaterialTheme.typography.body1)
        Text(entry.repository, style = MaterialTheme.typography.caption)
        if (entry.needsForceConfirmation) {
            Text("Confirmation required", style = MaterialTheme.typography.caption)
            TextButton(onClick = { actions.onRequestForceConfirmation(entry.worktreePath) }) {
                Text("Review force removal")
            }
            TextButton(onClick = { actions.onDismiss(entry.worktreePath) }) { Text("Dismiss") }
        } else if (entry.isFailed) {
            Text("Removal failed", style = MaterialTheme.typography.caption)
            entry.errorMessage?.let { Text(it, style = MaterialTheme.typography.caption) }
            TextButton(onClick = { actions.onRetry(entry.worktreePath) }) { Text("Retry") }
            TextButton(onClick = { actions.onDismiss(entry.worktreePath) }) { Text("Dismiss") }
        } else if (entry.isRemoving) {
            Text("Being removed", style = MaterialTheme.typography.caption)
            TooltipArea(
                tooltip = {
                    Surface(elevation = 4.dp) {
                        Text(
                            "This worktree is being removed and can no longer be canceled.",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                },
            ) {
                TextButton(onClick = {}, enabled = false) {
                    Text("Undo")
                }
            }
        } else {
            Text("${entry.remainingSeconds} seconds remaining", style = MaterialTheme.typography.caption)
            TextButton(onClick = { actions.onUndo(entry.worktreePath) }) {
                Text("Undo")
            }
        }
    }
}
