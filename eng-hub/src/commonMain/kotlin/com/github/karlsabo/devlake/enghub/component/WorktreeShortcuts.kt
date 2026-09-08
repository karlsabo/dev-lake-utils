package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OpenWorktreeShortcut(
    state: LocalWorktreeRowState,
    onOpen: () -> Unit,
) {
    val description = "Open worktree ${state.worktree.branch}"
    TooltipArea(
        tooltip = {
            Surface(elevation = 4.dp) {
                Text("Open worktree", modifier = Modifier.padding(8.dp))
            }
        },
    ) {
        IconButton(
            onClick = onOpen,
            enabled = isWorktreeOpenEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = description },
        ) {
            Text(text = "📂", style = MaterialTheme.typography.button)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArchiveWorktreeShortcut(
    state: LocalWorktreeRowState,
    onArchive: () -> Unit,
) {
    if (state.worktree.isRoot) return

    val description = "Archive worktree ${state.worktree.branch}"
    TooltipArea(
        tooltip = {
            Surface(elevation = 4.dp) {
                Text("Archive worktree", modifier = Modifier.padding(8.dp))
            }
        },
    ) {
        IconButton(
            onClick = onArchive,
            enabled = isWorktreeArchiveEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = description },
        ) {
            Text(text = "🗑️", style = MaterialTheme.typography.button)
        }
    }
}
