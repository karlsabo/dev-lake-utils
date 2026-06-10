package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun worktreePanel(
    state: WorktreePanelState,
    actions: WorktreePanelActions,
    modifier: Modifier = Modifier,
) {
    var pendingArchive by remember { mutableStateOf<PendingArchive?>(null) }
    var pendingCreateWorktree by remember { mutableStateOf<PendingCreateWorktree?>(null) }

    LaunchedEffect(state.repositoryCreateWorktreeRequest) {
        state.repositoryCreateWorktreeRequest?.let { request ->
            pendingCreateWorktree = request
            actions.onRepositoryCreateWorktreeRequestHandled()
        }
    }

    worktreeDialogHost(
        state = WorktreeDialogState(
            pendingArchive = pendingArchive,
            pendingCreateWorktree = pendingCreateWorktree,
            forceArchiveRequest = state.forceArchiveRequest,
        ),
        actions = WorktreeDialogActions(
            onPendingArchiveChange = { pendingArchive = it },
            onPendingCreateWorktreeChange = { pendingCreateWorktree = it },
            onArchiveWorktree = { archive ->
                actions.worktrees.onArchiveWorktree(archive.repoRootPath, archive.worktreePath)
            },
            onCreateWorktree = { request ->
                submitCreateWorktreeDialog(request, actions.worktrees.onCreateWorktree)
            },
            forceArchive = actions.forceArchive,
        ),
    )

    worktreePanelContent(
        state = state,
        actions = actions,
        onArchiveRequested = { pendingArchive = it },
        onCreateRequested = { pendingCreateWorktree = it },
        modifier = modifier,
    )
}

@Composable
private fun worktreePanelContent(
    state: WorktreePanelState,
    actions: WorktreePanelActions,
    onArchiveRequested: (PendingArchive) -> Unit,
    onCreateRequested: (PendingCreateWorktree) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        worktreePanelToolbar(onAddRepository = actions.onAddRepository)
        worktreeRepositoryList(
            state = state,
            actions = actions,
            onArchiveRequested = onArchiveRequested,
            onCreateRequested = onCreateRequested,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun worktreePanelToolbar(onAddRepository: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onAddRepository) {
            Text("Add Repository")
        }
    }
}

@Composable
private fun worktreeRepositoryList(
    state: WorktreePanelState,
    actions: WorktreePanelActions,
    onArchiveRequested: (PendingArchive) -> Unit,
    onCreateRequested: (PendingCreateWorktree) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        if (state.localRepositories.isEmpty()) {
            emptyWorktreeRepositoryList()
        } else {
            LazyColumn {
                items(state.localRepositories, key = { it.path }) { repository ->
                    localRepositoryRow(
                        state = WorktreeRowsState(
                            repository = repository,
                            setupStatuses = state.setupStatuses,
                            archivingWorktreePaths = state.archivingWorktreePaths,
                        ),
                        panelActions = actions,
                        onArchiveRequested = onArchiveRequested,
                        onCreateRequested = onCreateRequested,
                    )
                }
            }
        }
    }
}

@Composable
private fun emptyWorktreeRepositoryList() {
    Text(
        text = "No repositories configured",
        modifier = Modifier.padding(8.dp),
        style = MaterialTheme.typography.body2,
    )
}
