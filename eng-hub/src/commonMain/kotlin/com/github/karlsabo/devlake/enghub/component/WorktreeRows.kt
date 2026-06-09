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
    val nestingDepth: Int = 0,
)

internal data class VisibleWorktreeRow(
    val worktree: LocalWorktreeUiState,
    val nestingDepth: Int,
)

internal fun visibleWorktreeRows(worktrees: List<LocalWorktreeUiState>): List<VisibleWorktreeRow> {
    val parentBranches = worktrees.mapNotNull { it.parentBranch }
    if (parentBranches.isEmpty()) return worktrees.asFlatRows()

    val branchCounts = worktrees.groupingBy { it.branch }.eachCount()
    if (parentBranches.any { branchCounts[it] != 1 }) return worktrees.asFlatRows()

    val parentBranchByBranch = worktrees.associate { it.branch to it.parentBranch }
    if (worktrees.any { worktree -> worktree.parentBranch?.let { parentBranchByBranch[it] != null } == true }) {
        return worktrees.asFlatRows()
    }

    val childrenByParentBranch = worktrees
        .filter { it.parentBranch != null }
        .groupBy { it.parentBranch }
    return buildList {
        worktrees
            .filter { it.parentBranch == null }
            .forEach { worktree ->
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
