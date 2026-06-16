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
)

internal data class LocalWorktreeRowState(
    val worktree: LocalWorktreeUiState,
    val setupStatus: WorktreeSetupStatus?,
    val isArchiving: Boolean,
    val nestingDepth: Int = 0,
)

internal data class VisibleWorktreeRow(
    val worktree: LocalWorktreeUiState,
    val nestingDepth: Int,
)

internal fun visibleWorktreeRows(worktrees: List<LocalWorktreeUiState>): List<VisibleWorktreeRow> {
    val visibleRows = if (worktrees.hasValidParentBranchNesting()) {
        worktrees.asNestedRows()
    } else {
        worktrees.asFlatRows()
    }
    return visibleRows
}

private fun List<LocalWorktreeUiState>.hasValidParentBranchNesting(): Boolean {
    val parentBranches = mapNotNull { it.parentBranch }
    val branchCounts = groupingBy { it.branch }.eachCount()
    val parentBranchByBranch = associate { it.branch to it.parentBranch }
    return parentBranches.isNotEmpty() &&
        parentBranches.all { branchCounts[it] == 1 } &&
        none { worktree -> worktree.parentBranch?.let { parentBranchByBranch[it] != null } == true }
}

private fun List<LocalWorktreeUiState>.asNestedRows(): List<VisibleWorktreeRow> {
    val childrenByParentBranch = filter { it.parentBranch != null }.groupBy { it.parentBranch }
    return buildList {
        this@asNestedRows.filter { it.parentBranch == null }.forEach { worktree ->
            add(VisibleWorktreeRow(worktree = worktree, nestingDepth = 0))
            childrenByParentBranch[worktree.branch].orEmpty().forEach { child ->
                add(VisibleWorktreeRow(worktree = child, nestingDepth = 1))
            }
        }
    }
}

private fun List<LocalWorktreeUiState>.asFlatRows(): List<VisibleWorktreeRow> = map { worktree ->
    VisibleWorktreeRow(worktree = worktree, nestingDepth = 0)
}

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
): Boolean = setupStatus == null && !isArchiving && worktree.hasCreatableBase()

internal fun isWorktreeArchiveEnabled(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = setupStatus == null && !isArchiving

private fun LocalWorktreeUiState.hasCreatableBase(): Boolean = branch != DETACHED || !baseCommitHash.isNullOrBlank()
