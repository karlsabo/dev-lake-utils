package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.git.WorktreePath

@Composable
internal fun localRepositoryRow(
    state: WorktreeRowsState,
    panelActions: WorktreePanelActions,
    onArchiveRequested: (PendingArchive) -> Unit,
    onCreateRequested: (PendingCreateWorktree) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            localRepositoryHeader(
                repository = state.repository,
                onToggleRepository = { panelActions.onToggleRepository(state.repository.path) },
            )
            Text(text = state.repository.path, style = MaterialTheme.typography.caption)
            localWorktreeRows(
                state = state,
                panelActions = panelActions,
                onArchiveRequested = onArchiveRequested,
                onCreateRequested = onCreateRequested,
            )
        }
    }
}

@Composable
private fun localRepositoryHeader(
    repository: LocalRepositoryUiState,
    onToggleRepository: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleRepository,
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = repositoryToggleDescription(repository) },
        ) {
            Text(
                text = if (repository.isExpanded) "-" else "+",
                style = MaterialTheme.typography.button,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = repository.name,
            style = MaterialTheme.typography.subtitle1,
        )
    }
}

@Composable
private fun localWorktreeRows(
    state: WorktreeRowsState,
    panelActions: WorktreePanelActions,
    onArchiveRequested: (PendingArchive) -> Unit,
    onCreateRequested: (PendingCreateWorktree) -> Unit,
) {
    if (state.repository.isExpanded && state.repository.worktrees.isNotEmpty()) {
        Spacer(modifier = Modifier.size(8.dp))
        visibleWorktreeRows(state.repository.worktrees).forEach { row ->
            val worktree = row.worktree
            val normalizedWorktreePath = worktree.path.normalizedWorktreePath()
            key(normalizedWorktreePath) {
                localWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = worktree,
                        setupStatus = state.setupStatuses[WorktreePath(normalizedWorktreePath)],
                        isArchiving = normalizedWorktreePath in state.archivingWorktreePaths,
                        nestingDepth = row.nestingDepth,
                    ),
                    onOpen = { panelActions.worktrees.onOpenWorktree(state.repository.path, worktree.path) },
                    onArchive = { onArchiveRequested(PendingArchive(state.repository.path, worktree.path)) },
                    onOpenCreateWorktreeDialog = {
                        onCreateRequested(createWorktreeDialogState(state.repository.path, worktree))
                    },
                )
            }
        }
    }
}

private fun repositoryToggleDescription(repository: LocalRepositoryUiState): String = if (repository.isExpanded) {
    "Collapse ${repository.name}"
} else {
    "Expand ${repository.name}"
}

private fun String.normalizedWorktreePath(): String = trim().trimEnd('/', '\\')
