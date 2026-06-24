package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

private const val DETACHED = "(detached)"

internal enum class RepositoryMenuAction {
    CreateWorktree,
}

internal enum class WorktreeMenuAction {
    Open,
    CreateWorktree,
    RebaseOntoParent,
    Archive,
}

internal data class WorktreeRowsState(
    val repository: LocalRepositoryUiState,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    val archivingWorktreePaths: Set<String>,
    val rebasingWorktreePaths: Set<String> = emptySet(),
)

internal data class LocalWorktreeRowState(
    val worktree: LocalWorktreeUiState,
    val setupStatus: WorktreeSetupStatus?,
    val isArchiving: Boolean,
    val isRebasing: Boolean = false,
    val nestingDepth: Int = 0,
)

internal data class VisibleWorktreeRow(
    val worktree: LocalWorktreeUiState,
    val nestingDepth: Int,
)

internal fun visibleRepositoryMenuActions(repository: LocalRepositoryUiState): List<RepositoryMenuAction> = buildList {
    if (repository.path.isNotBlank()) add(RepositoryMenuAction.CreateWorktree)
}

internal fun isRepositoryCreateWorktreeEnabled(
    repository: LocalRepositoryUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = repository.path.isNotBlank() && setupStatus == null && !isArchiving

internal fun visibleWorktreeMenuActions(worktree: LocalWorktreeUiState): List<WorktreeMenuAction> = buildList {
    add(WorktreeMenuAction.Open)
    add(WorktreeMenuAction.CreateWorktree)
    if (!worktree.parentBranch.isNullOrBlank()) add(WorktreeMenuAction.RebaseOntoParent)
    if (!worktree.isRoot) add(WorktreeMenuAction.Archive)
}

internal fun isWorktreeCreateEnabled(
    worktree: LocalWorktreeUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isRebasing: Boolean = false,
): Boolean = setupStatus == null && !isArchiving && !isRebasing && worktree.hasCreatableBase()

internal fun isWorktreeArchiveEnabled(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isRebasing: Boolean = false,
): Boolean = setupStatus == null && !isArchiving && !isRebasing

internal fun isWorktreeRebaseEnabled(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isRebasing: Boolean = false,
): Boolean = setupStatus == null && !isArchiving && !isRebasing

private fun LocalWorktreeUiState.hasCreatableBase(): Boolean = branch != DETACHED || !baseCommitHash.isNullOrBlank()
