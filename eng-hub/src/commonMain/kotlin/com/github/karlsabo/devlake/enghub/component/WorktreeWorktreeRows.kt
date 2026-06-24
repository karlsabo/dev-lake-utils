package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus

private const val WORKTREE_ROW_BASE_INDENT_DP = 16
private const val WORKTREE_ROW_CHILD_INDENT_DP = 24

internal fun worktreeRowIndentDp(nestingDepth: Int): Int {
    val childIndentDp = WORKTREE_ROW_CHILD_INDENT_DP * nestingDepth.coerceAtLeast(0)
    return WORKTREE_ROW_BASE_INDENT_DP + childIndentDp
}

internal data class LocalWorktreeRowActions(
    val onOpen: () -> Unit,
    val onArchive: () -> Unit,
    val onOpenCreateWorktreeDialog: () -> Unit,
    val onRebaseOntoParent: () -> Unit,
)

@Composable
internal fun LocalWorktreeRow(
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
        Spacer(modifier = Modifier.width(worktreeRowIndentDp(state.nestingDepth).dp))
        WorktreeDirtyIndicator(state.worktree)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.worktree.branch,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
        )
        WorktreeRebaseNeededIndicator(state.worktree)
        WorktreeProgressLabels(state.setupStatus, state.isArchiving, state.isRebasing)
        LocalWorktreeActionMenu(state = state, actions = actions)
    }
}

@Composable
private fun WorktreeDirtyIndicator(worktree: LocalWorktreeUiState) {
    Text(
        text = if (worktree.isDirty) "🟡" else "🟢",
        modifier = Modifier.semantics {
            contentDescription = if (worktree.isDirty) "Dirty worktree" else "Clean worktree"
        },
        style = MaterialTheme.typography.body2,
    )
}

@Composable
private fun WorktreeRebaseNeededIndicator(worktree: LocalWorktreeUiState) {
    if (worktree.needsRebase) {
        Row {
            Text(
                text = "Rebase needed",
                modifier = Modifier.semantics { contentDescription = "Rebase needed" },
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun WorktreeProgressLabels(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isRebasing: Boolean,
) {
    Row {
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
}
