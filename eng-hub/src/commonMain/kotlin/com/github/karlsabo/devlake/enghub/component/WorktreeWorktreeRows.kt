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

private const val WORKTREE_ROW_BASE_INDENT_DP = 16
private const val WORKTREE_ROW_CHILD_INDENT_DP = 24
private const val WORKTREE_ROW_MAX_NESTING_DEPTH = 1

private data class LocalWorktreeRowActions(
    val onOpen: () -> Unit,
    val onArchive: () -> Unit,
    val onOpenCreateWorktreeDialog: () -> Unit,
    val onRebaseOntoParent: () -> Unit,
)

@Composable
internal fun localWorktreeRow(
    state: LocalWorktreeRowState,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onOpenCreateWorktreeDialog: () -> Unit,
    onRebaseOntoParent: () -> Unit = {},
) {
    val actions = LocalWorktreeRowActions(
        onOpen = onOpen,
        onArchive = onArchive,
        onOpenCreateWorktreeDialog = onOpenCreateWorktreeDialog,
        onRebaseOntoParent = onRebaseOntoParent,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val indent = WORKTREE_ROW_BASE_INDENT_DP +
            WORKTREE_ROW_CHILD_INDENT_DP * state.nestingDepth.coerceIn(0, WORKTREE_ROW_MAX_NESTING_DEPTH)
        Spacer(modifier = Modifier.width(indent.dp))
        worktreeDirtyIndicator(state.worktree)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.worktree.branch,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
        )
        worktreeRebaseNeededIndicator(state.worktree)
        worktreeProgressLabels(state.setupStatus, state.isArchiving, state.isRebasing)
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
private fun worktreeRebaseNeededIndicator(worktree: LocalWorktreeUiState) {
    if (worktree.needsRebase) {
        Text(
            text = "Rebase needed",
            modifier = Modifier.semantics { contentDescription = "Rebase needed" },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.error,
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun worktreeProgressLabels(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isRebasing: Boolean,
) {
    setupStatus?.let {
        Text(text = it.setupStatusLabel(), style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.width(8.dp))
    }
    if (isArchiving) {
        Text(text = "Archiving...", style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.width(8.dp))
    }
    if (isRebasing) {
        Text(text = "Rebasing...", style = MaterialTheme.typography.caption)
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
        WorktreeMenuAction.RebaseOntoParent -> rebaseOntoParentMenuItem(state, rowActions, onMenuDismiss)
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
        enabled = state.setupStatus == null && !state.isArchiving && !state.isRebasing,
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
        enabled = isWorktreeCreateEnabled(state.worktree, state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Create worktree")
    }
}

@Composable
private fun rebaseOntoParentMenuItem(
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
        enabled = isWorktreeArchiveEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Archive")
    }
}
