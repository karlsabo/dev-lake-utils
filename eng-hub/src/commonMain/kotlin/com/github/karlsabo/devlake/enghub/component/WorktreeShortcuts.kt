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

private const val UPDATE_SHORTCUT_GLYPH = "⬇️"

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
            enabled = isWorktreeOpenEnabled(
                setupStatus = state.setupStatus,
                isArchiving = state.isArchiving,
                isUpdating = state.isUpdating,
                isRebasing = state.isRebasing,
                isMerging = state.isMerging,
            ),
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
internal fun UpdateWorktreeShortcut(
    state: LocalWorktreeRowState,
    onUpdate: () -> Unit,
) {
    val integrationTargetBranch = state.worktree.integrationTargetBranch
    val updatesFromOrigin = state.worktree.canUpdateFromOrigin
    if (!updatesFromOrigin && integrationTargetBranch.isNullOrBlank()) return

    val tooltip = if (updatesFromOrigin) {
        "Update from origin"
    } else {
        "Update from base $integrationTargetBranch"
    }
    val description = if (updatesFromOrigin) {
        "Update worktree ${state.worktree.branch} from origin"
    } else {
        "Update ${state.worktree.branch} from base $integrationTargetBranch"
    }
    WorktreeIntegrationShortcut(
        tooltip = tooltip,
        description = description,
        glyph = UPDATE_SHORTCUT_GLYPH,
        enabled = isWorktreeOpenEnabled(
            setupStatus = state.setupStatus,
            isArchiving = state.isArchiving,
            isUpdating = state.isUpdating,
            isRebasing = state.isRebasing,
            isMerging = state.isMerging,
        ),
        onClick = onUpdate,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorktreeIntegrationShortcut(
    tooltip: String,
    description: String,
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(elevation = 4.dp) {
                Text(tooltip, modifier = Modifier.padding(8.dp))
            }
        },
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = description },
        ) {
            Text(text = glyph, style = MaterialTheme.typography.button)
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
            enabled = isWorktreeArchiveEnabled(
                setupStatus = state.setupStatus,
                isArchiving = state.isArchiving,
                isUpdating = state.isUpdating,
                isRebasing = state.isRebasing,
                isMerging = state.isMerging,
            ),
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = description },
        ) {
            Text(text = "🗑️", style = MaterialTheme.typography.button)
        }
    }
}
