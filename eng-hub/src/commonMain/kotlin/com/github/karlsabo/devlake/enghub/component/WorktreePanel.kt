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
internal fun WorktreePanel(
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

    WorktreeDialogHost(
        state = WorktreeDialogState(
            pendingArchive = pendingArchive,
            pendingCreateWorktree = pendingCreateWorktree,
            useUnrelatedExistingBranchConfirmationRequest = state.useUnrelatedExistingBranchConfirmationRequest,
            rebaseConflictResolutionRequest = state.rebaseConflictResolutionRequest,
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
            onConfirmUseUnrelatedExistingBranch = actions.onConfirmUseUnrelatedExistingBranch,
            onDismissUseUnrelatedExistingBranchConfirmation = actions.onDismissUseUnrelatedExistingBranchConfirmation,
            onAbortRebaseConflict = actions.onAbortRebaseConflict,
            onLeaveRebaseConflictAsIs = actions.onLeaveRebaseConflictAsIs,
            forceArchive = actions.forceArchive,
        ),
    )

    WorktreePanelContent(
        state = state,
        actions = actions,
        onArchiveRequest = { pendingArchive = it },
        onCreateRequest = { pendingCreateWorktree = it },
        modifier = modifier,
    )
}

@Composable
private fun WorktreePanelContent(
    state: WorktreePanelState,
    actions: WorktreePanelActions,
    onArchiveRequest: (PendingArchive) -> Unit,
    onCreateRequest: (PendingCreateWorktree) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        WorktreePanelToolbar(onAddRepository = actions.onAddRepository)
        WorktreeRepositoryList(
            state = state,
            actions = actions,
            onArchiveRequest = onArchiveRequest,
            onCreateRequest = onCreateRequest,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun WorktreePanelToolbar(onAddRepository: () -> Unit) {
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
private fun WorktreeRepositoryList(
    state: WorktreePanelState,
    actions: WorktreePanelActions,
    onArchiveRequest: (PendingArchive) -> Unit,
    onCreateRequest: (PendingCreateWorktree) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (state.localRepositories.isEmpty()) {
            EmptyWorktreeRepositoryList()
        } else {
            LazyColumn {
                items(state.localRepositories, key = { it.path }) { repository ->
                    LocalRepositoryRow(
                        state = WorktreeRowsState(
                            repository = repository,
                            setupStatuses = state.setupStatuses,
                            archivingWorktreePaths = state.archivingWorktreePaths,
                            rebasingWorktreePaths = state.rebasingWorktreePaths,
                        ),
                        panelActions = actions,
                        onArchiveRequest = onArchiveRequest,
                        onCreateRequest = onCreateRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWorktreeRepositoryList() {
    Text(
        text = "No repositories configured",
        modifier = Modifier.padding(8.dp),
        style = MaterialTheme.typography.body2,
    )
}
