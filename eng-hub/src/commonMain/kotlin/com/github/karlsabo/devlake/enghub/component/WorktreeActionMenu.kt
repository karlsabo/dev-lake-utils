package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalWorktreeActionMenu(
    state: LocalWorktreeRowState,
    actions: LocalWorktreeRowActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            enabled = !state.isArchiving && !state.isRebasing,
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = "Worktree actions for ${state.worktree.branch}" },
        ) {
            Text(text = "⋮", style = MaterialTheme.typography.button)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            visibleWorktreeMenuActions(state.worktree).forEach { action ->
                LocalWorktreeMenuItem(
                    action = action,
                    state = state,
                    rowActions = actions,
                    onMenuDismiss = { menuExpanded = false },
                )
            }
        }
    }
}

@Composable
private fun LocalWorktreeMenuItem(
    action: WorktreeMenuAction,
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    when (action) {
        WorktreeMenuAction.Open -> OpenWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.CreateWorktree -> CreateWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.RebaseOntoParent -> RebaseOntoParentMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.Archive -> ArchiveWorktreeMenuItem(state, rowActions, onMenuDismiss)
    }
}

@Composable
private fun OpenWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpen()
        },
        enabled = state.setupStatus == null && !state.isArchiving && !state.isRebasing,
    ) {
        Text(setupActionLabel(defaultLabel = "Open", setupStatus = state.setupStatus))
    }
}

@Composable
private fun CreateWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpenCreateWorktreeDialog()
        },
        enabled = isWorktreeCreateEnabled(state.worktree, state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Create worktree")
    }
}

@Composable
private fun RebaseOntoParentMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onRebaseOntoParent()
        },
        enabled = isWorktreeRebaseEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Rebase onto parent")
    }
}

@Composable
private fun ArchiveWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onArchive()
        },
        enabled = isWorktreeArchiveEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Archive")
    }
}
