package com.github.karlsabo.devlake.enghub.component

internal fun emptyLocalWorktreeRowActions(): LocalWorktreeRowActions = LocalWorktreeRowActions(
    onOpen = {},
    onOpenPullRequest = {},
    onArchive = {},
    onOpenCreateWorktreeDialog = {},
    onRebaseOntoParent = {},
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
    onAbortRebaseConflict = {},
    onLeaveRebaseConflictAsIs = {},
    worktrees = LocalWorktreeActions(
        onOpenWorktree = { _, _ -> },
        onOpenPullRequest = {},
        onArchiveWorktree = { _, _ -> },
        onCreateWorktree = {},
        onRebaseOntoParent = { _, _, _ -> },
    ),
    forceArchive = ForceArchiveWorktreeActions(
        onConfirm = { _, _ -> },
        onDismiss = {},
    ),
)
