package com.github.karlsabo.devlake.enghub.component

import androidx.compose.runtime.Composable
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

internal fun worktreeRowState(isDirty: Boolean? = false): LocalWorktreeRowState = LocalWorktreeRowState(
    worktree = LocalWorktreeUiState(
        branch = "feature/login",
        path = "/repos/dev-lake-utils-feature-login",
        isDirty = isDirty,
        parentBranch = "main",
    ),
    setupStatus = null,
    isArchiving = false,
)

@Composable
internal fun WorktreeRow(
    state: LocalWorktreeRowState = worktreeRowState(),
    actions: LocalWorktreeRowActions = emptyLocalWorktreeRowActions(),
) {
    LocalWorktreeRow(state = state, actions = actions)
}

internal fun emptyLocalWorktreeRowActions(): LocalWorktreeRowActions = LocalWorktreeRowActions(
    onOpen = {},
    onOpenPullRequest = {},
    onArchive = {},
    onOpenCreateWorktreeDialog = {},
    onUpdate = {},
    onRebaseOntoParent = {},
    onMergeOntoParent = {},
)

internal fun emptyPanelActions(): WorktreePanelActions = WorktreePanelActions(
    onAddRepository = {},
    onToggleRepository = {},
    onCreateWorktreeFromRepository = {},
    onRepositoryCreateWorktreeRequestHandled = {},
    onDiscoverExistingBranches = {},
    onDiscoverExistingPullRequest = { _, _ -> },
    onCheckoutExistingBranch = { _, _, _ -> },
    onConfirmUseUnrelatedExistingBranch = {},
    onDismissUseUnrelatedExistingBranchConfirmation = {},
    onAbortWorktreeConflict = {},
    onLeaveWorktreeConflictAsIs = {},
    worktrees = LocalWorktreeActions(
        onOpenWorktree = { _, _ -> },
        onOpenPullRequest = {},
        onArchiveWorktree = { _, _ -> },
        onCreateWorktree = {},
        onUpdateFromOrigin = { _, _, _ -> },
        onUpdateFromParent = { _, _, _ -> },
        onRebaseOntoParent = { _, _, _ -> },
        onMergeOntoParent = { _, _, _ -> },
    ),
    forceArchive = ForceArchiveWorktreeActions(
        onConfirm = {},
        onDismiss = {},
    ),
)
