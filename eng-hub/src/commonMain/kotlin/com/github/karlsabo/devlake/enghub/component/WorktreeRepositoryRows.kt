package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.viewmodel.connectedPullRequest
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
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val backgroundColor = if (isHovered) {
        MaterialTheme.colors.onSurface.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val normalizedRepositoryPath = repository.normalizedPathOrNull()
    val repositoryStatus = state.setupStatuses[WorktreePath(repository.path)]
    val isRepositoryArchiving = normalizedRepositoryPath != null &&
        normalizedRepositoryPath in state.archivingWorktreePaths
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .hoverable(hoverInteractionSource)
            .testTag("repository-header-${repository.name}"),
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
        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(onToggleRepository) {
                    detectTapGestures(onDoubleTap = { onToggleRepository() })
                }
                .testTag("repository-header-content-${repository.name}"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = repository.name,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.subtitle1,
            )
        }
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
    if (!state.repository.isExpanded) return

    Column {
        if (state.repository.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(24.dp)
                    .semantics {
                        contentDescription = "Loading worktrees for ${state.repository.name}"
                    },
                strokeWidth = 2.dp,
            )
        }
        if (state.repository.worktrees.isNotEmpty()) {
            Spacer(modifier = Modifier.size(8.dp))
            visibleWorktreeRows(state.repository.worktrees).forEach { row ->
                val worktree = row.worktree
                val normalizedWorktreePath = worktree.path.normalizedRepositoryPath()
                val connectedPullRequest = state.connectedPullRequestFor(worktree.branch)
                key(normalizedWorktreePath) {
                    LocalWorktreeRow(
                        state = LocalWorktreeRowState(
                            worktree = worktree,
                            setupStatus = state.setupStatuses[WorktreePath(worktree.path)],
                            isArchiving = normalizedWorktreePath in state.archivingWorktreePaths,
                            isRebasing = normalizedWorktreePath in state.rebasingWorktreePaths,
                            nestingDepth = row.nestingDepth,
                            connectedPullRequest = connectedPullRequest,
                        ),
                        actions = LocalWorktreeRowActions(
                            onOpen = {
                                panelActions.worktrees.onOpenWorktree(state.repository.path, worktree.path)
                            },
                            onOpenPullRequest = panelActions.worktrees.onOpenPullRequest,
                            onArchive = {
                                onArchiveRequest(PendingArchive(state.repository.path, worktree.path))
                            },
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
                        ),
                    )
                }
            }
        }
    }
}

private fun WorktreeRowsState.connectedPullRequestFor(branch: String) = connectedPullRequest(
    repositoryIdentity = repository.repositoryIdentity,
    branch = branch,
    pullRequests = authoredOpenPullRequests,
)

private fun repositoryToggleDescription(repository: LocalRepositoryUiState): String = if (repository.isExpanded) {
    "Collapse ${repository.name}"
} else {
    "Expand ${repository.name}"
}

private fun LocalRepositoryUiState.normalizedPathOrNull(): String? {
    val normalizedPath = path.normalizedRepositoryPath()
    return normalizedPath.takeIf { it.isNotEmpty() }
}
