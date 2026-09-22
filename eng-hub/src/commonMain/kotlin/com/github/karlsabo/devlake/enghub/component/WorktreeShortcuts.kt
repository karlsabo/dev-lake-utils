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
private const val REBASE_SHORTCUT_GLYPH = "🔁"
private const val MERGE_SHORTCUT_GLYPH = "🔀"

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
    onUpdateFromOrigin: () -> Unit,
) {
    if (!state.worktree.canUpdateFromOrigin) return

    WorktreeIntegrationShortcut(
        tooltip = "Update from origin",
        description = "Update worktree ${state.worktree.branch} from origin",
        glyph = UPDATE_SHORTCUT_GLYPH,
        enabled = isWorktreeOpenEnabled(
            setupStatus = state.setupStatus,
            isArchiving = state.isArchiving,
            isUpdating = state.isUpdating,
            isRebasing = state.isRebasing,
            isMerging = state.isMerging,
        ),
        onClick = onUpdateFromOrigin,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RebaseWorktreeShortcut(
    state: LocalWorktreeRowState,
    onRebaseOntoParent: () -> Unit,
) {
    val parentBranch = state.worktree.parentBranch
    if (parentBranch.isNullOrBlank() || state.worktree.canUpdateFromOrigin) return

    WorktreeIntegrationShortcut(
        tooltip = "Rebase onto parent",
        description = "Rebase worktree ${state.worktree.branch} onto $parentBranch",
        glyph = REBASE_SHORTCUT_GLYPH,
        enabled = isWorktreeRebaseEnabled(
            setupStatus = state.setupStatus,
            isArchiving = state.isArchiving,
            isUpdating = state.isUpdating,
            isRebasing = state.isRebasing,
            isMerging = state.isMerging,
        ),
        onClick = onRebaseOntoParent,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MergeWorktreeShortcut(
    state: LocalWorktreeRowState,
    onMergeOntoParent: () -> Unit,
) {
    val parentBranch = state.worktree.parentBranch
    if (parentBranch.isNullOrBlank() || state.worktree.canUpdateFromOrigin) return

    WorktreeIntegrationShortcut(
        tooltip = "Merge parent into worktree",
        description = "Merge parent $parentBranch into worktree ${state.worktree.branch}",
        glyph = MERGE_SHORTCUT_GLYPH,
        enabled = isWorktreeMergeEnabled(
            setupStatus = state.setupStatus,
            isArchiving = state.isArchiving,
            isUpdating = state.isUpdating,
            isRebasing = state.isRebasing,
            isMerging = state.isMerging,
        ),
        onClick = onMergeOntoParent,
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
