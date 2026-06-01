package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal enum class WorktreeMenuAction {
    Open,
    CreateWorktree,
    Archive,
}

internal data class WorktreeRowsState(
    val repository: LocalRepositoryUiState,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    val archivingWorktreePaths: Set<String>,
)

internal data class LocalWorktreeRowState(
    val worktree: LocalWorktreeUiState,
    val setupStatus: WorktreeSetupStatus?,
    val isArchiving: Boolean,
)

internal fun visibleWorktreeMenuActions(worktree: LocalWorktreeUiState): List<WorktreeMenuAction> = buildList {
    add(WorktreeMenuAction.Open)
    add(WorktreeMenuAction.CreateWorktree)
    if (!worktree.isRoot) add(WorktreeMenuAction.Archive)
}

internal fun isWorktreeCreateEnabled(
    worktree: LocalWorktreeUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = setupStatus == null && !isArchiving && !worktree.isDetachedDisplayBranch()

internal fun isWorktreeArchiveEnabled(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = setupStatus == null && !isArchiving

private fun LocalWorktreeUiState.isDetachedDisplayBranch(): Boolean = branch == "(detached)"
