package com.github.karlsabo.devlake.enghub.component

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
        onConfirm = { _, _ -> },
        onDismiss = {},
    ),
)
