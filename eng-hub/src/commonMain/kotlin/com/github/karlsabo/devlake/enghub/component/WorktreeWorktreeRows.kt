package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus

private data class LocalWorktreeRowActions(
    val onOpen: () -> Unit,
    val onArchive: () -> Unit,
    val onOpenCreateWorktreeDialog: () -> Unit,
)

@Composable
internal fun localWorktreeRow(
    state: LocalWorktreeRowState,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onOpenCreateWorktreeDialog: () -> Unit,
) {
    val actions = LocalWorktreeRowActions(
        onOpen = onOpen,
        onArchive = onArchive,
        onOpenCreateWorktreeDialog = onOpenCreateWorktreeDialog,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        worktreeDirtyIndicator(state.worktree)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.worktree.branch,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
        )
        worktreeProgressLabels(state.setupStatus, state.isArchiving)
        localWorktreeActionMenu(state = state, actions = actions)
    }
}

@Composable
private fun worktreeDirtyIndicator(worktree: LocalWorktreeUiState) {
    Text(
        text = if (worktree.isDirty) "🟡" else "🟢",
        modifier = Modifier.semantics {
            contentDescription = if (worktree.isDirty) "Dirty worktree" else "Clean worktree"
        },
        style = MaterialTheme.typography.body2,
    )
}

@Composable
private fun worktreeProgressLabels(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
) {
    setupStatus?.let {
        Text(text = it.setupStatusLabel(), style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.width(8.dp))
    }
    if (isArchiving) {
        Text(text = "Archiving...", style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun localWorktreeActionMenu(
    state: LocalWorktreeRowState,
    actions: LocalWorktreeRowActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            enabled = !state.isArchiving,
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
                localWorktreeMenuItem(
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
private fun localWorktreeMenuItem(
    action: WorktreeMenuAction,
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    when (action) {
        WorktreeMenuAction.Open -> openWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.CreateWorktree -> createWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.Archive -> archiveWorktreeMenuItem(state, rowActions, onMenuDismiss)
    }
}

@Composable
private fun openWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpen()
        },
        enabled = !state.hasBlockedOpenAction(),
    ) {
        Text(setupActionLabel(defaultLabel = "Open", setupStatus = state.setupStatus))
    }
}

@Composable
private fun createWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpenCreateWorktreeDialog()
        },
        enabled = isWorktreeCreateEnabled(state.worktree, state.setupStatus, state.isArchiving),
    ) {
        Text("Create worktree")
    }
}

@Composable
private fun archiveWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onArchive()
        },
        enabled = isWorktreeArchiveEnabled(state.setupStatus, state.isArchiving),
    ) {
        Text("Archive")
    }
}

private fun LocalWorktreeRowState.hasBlockedOpenAction(): Boolean = setupStatus != null || isArchiving
