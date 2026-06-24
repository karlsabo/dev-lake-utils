package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

@Composable
internal fun LocalRepositoryRow(
    state: WorktreeRowsState,
    panelActions: WorktreePanelActions,
    onArchiveRequest: (PendingArchive) -> Unit,
    onCreateRequest: (PendingCreateWorktree) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            LocalRepositoryHeader(
                state = state,
                onToggleRepository = { panelActions.onToggleRepository(state.repository.path) },
                onCreateWorktreeFromRepository = { panelActions.onCreateWorktreeFromRepository(state.repository.path) },
            )
            Text(text = state.repository.path, style = MaterialTheme.typography.caption)
            LocalWorktreeRows(
                state = state,
                panelActions = panelActions,
                onArchiveRequest = onArchiveRequest,
                onCreateRequest = onCreateRequest,
            )
        }
    }
}

@Composable
private fun LocalRepositoryHeader(
    state: WorktreeRowsState,
    onToggleRepository: () -> Unit,
    onCreateWorktreeFromRepository: () -> Unit,
) {
    val repository = state.repository
    val normalizedRepositoryPath = repository.normalizedPathOrNull()
    val repositoryStatus = normalizedRepositoryPath?.let { state.setupStatuses[WorktreePath(it)] }
    val isRepositoryArchiving = normalizedRepositoryPath != null &&
        normalizedRepositoryPath in state.archivingWorktreePaths
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
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.subtitle1,
        )
        LocalRepositoryActionMenu(
            repository = repository,
            setupStatus = repositoryStatus,
            isArchiving = isRepositoryArchiving,
            onCreateWorktreeFromRepository = onCreateWorktreeFromRepository,
        )
    }
}

@Composable
private fun LocalRepositoryActionMenu(
    repository: LocalRepositoryUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    onCreateWorktreeFromRepository: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = "Repository actions for ${repository.name}" },
        ) {
            Text(text = "⋮", style = MaterialTheme.typography.button)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            visibleRepositoryMenuActions(repository).forEach { action ->
                LocalRepositoryMenuItem(
                    action = action,
                    repository = repository,
                    setupStatus = setupStatus,
                    isArchiving = isArchiving,
                    onCreateWorktreeFromRepository = {
                        menuExpanded = false
                        onCreateWorktreeFromRepository()
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalRepositoryMenuItem(
    action: RepositoryMenuAction,
    repository: LocalRepositoryUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    onCreateWorktreeFromRepository: () -> Unit,
) {
    when (action) {
        RepositoryMenuAction.CreateWorktree -> DropdownMenuItem(
            onClick = onCreateWorktreeFromRepository,
            enabled = isRepositoryCreateWorktreeEnabled(repository, setupStatus, isArchiving),
        ) {
            Text("Create worktree")
        }
    }
}

@Composable
private fun LocalWorktreeRows(
    state: WorktreeRowsState,
    panelActions: WorktreePanelActions,
    onArchiveRequest: (PendingArchive) -> Unit,
    onCreateRequest: (PendingCreateWorktree) -> Unit,
) {
    if (state.repository.isExpanded && state.repository.worktrees.isNotEmpty()) {
        Spacer(modifier = Modifier.size(8.dp))
        visibleWorktreeRows(state.repository.worktrees).forEach { row ->
            val worktree = row.worktree
            val normalizedWorktreePath = worktree.path.normalizedWorktreePath()
            key(normalizedWorktreePath) {
                LocalWorktreeRow(
                    state = LocalWorktreeRowState(
                        worktree = worktree,
                        setupStatus = state.setupStatuses[WorktreePath(normalizedWorktreePath)],
                        isArchiving = normalizedWorktreePath in state.archivingWorktreePaths,
                        isRebasing = normalizedWorktreePath in state.rebasingWorktreePaths,
                        nestingDepth = row.nestingDepth,
                    ),
                    onOpen = { panelActions.worktrees.onOpenWorktree(state.repository.path, worktree.path) },
                    onArchive = { onArchiveRequest(PendingArchive(state.repository.path, worktree.path)) },
                    onOpenCreateWorktreeDialog = {
                        onCreateRequest(createWorktreeDialogState(state.repository.path, worktree))
                    },
                    onRebaseOntoParent = {
                        worktree.parentBranch?.let { parentBranch ->
                            panelActions.worktrees.onRebaseOntoParent(
                                state.repository.path,
                                worktree.path,
                                parentBranch,
                            )
                        }
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

private fun LocalRepositoryUiState.normalizedPathOrNull(): String? {
    val normalizedPath = path.normalizedWorktreePath()
    return normalizedPath.takeIf { it.isNotEmpty() }
}

private fun String.normalizedWorktreePath(): String = trim().trimEnd('/', '\\')
