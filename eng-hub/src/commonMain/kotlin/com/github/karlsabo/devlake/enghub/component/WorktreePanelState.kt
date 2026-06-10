package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal typealias CreateWorktreeCallback = (
    repoRootPath: String,
    baseWorktreePath: String,
    baseBranch: String,
    targetBranch: String,
) -> Unit

internal data class WorktreePanelState(
    val localRepositories: List<LocalRepositoryUiState>,
    val forceArchiveRequest: ForceArchiveWorktreeUiState?,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    val archivingWorktreePaths: Set<String>,
    val repositoryCreateWorktreeRequest: PendingCreateWorktree? = null,
)

internal data class WorktreePanelActions(
    val onAddRepository: () -> Unit,
    val onToggleRepository: (String) -> Unit,
    val onCreateWorktreeFromRepository: (String) -> Unit,
    val onRepositoryCreateWorktreeRequestHandled: () -> Unit,
    val worktrees: LocalWorktreeActions,
    val forceArchive: ForceArchiveWorktreeActions,
)

internal data class LocalWorktreeActions(
    val onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    val onArchiveWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    val onCreateWorktree: CreateWorktreeCallback,
)

internal data class ForceArchiveWorktreeActions(
    val onConfirm: (repoRootPath: String, worktreePath: String) -> Unit,
    val onDismiss: () -> Unit,
)

internal data class PendingCreateWorktree(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String = "",
)

internal data class PendingArchive(
    val repoRootPath: String,
    val worktreePath: String,
)

internal fun createWorktreeDialogState(
    repoRootPath: String,
    worktree: LocalWorktreeUiState,
): PendingCreateWorktree = createRepositoryWorktreeDialogState(
    repoRootPath = repoRootPath,
    baseWorktreePath = worktree.path,
    baseBranch = worktree.branch,
)

internal fun createRepositoryWorktreeDialogState(
    repoRootPath: String,
    baseWorktreePath: String,
    baseBranch: String,
): PendingCreateWorktree = PendingCreateWorktree(
    repoRootPath = repoRootPath,
    baseWorktreePath = baseWorktreePath,
    baseBranch = baseBranch,
)

internal fun submitCreateWorktreeDialog(
    state: PendingCreateWorktree,
    onCreateWorktree: CreateWorktreeCallback,
) {
    onCreateWorktree(state.repoRootPath, state.baseWorktreePath, state.baseBranch, state.targetBranch)
}
