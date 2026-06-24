package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

internal fun visibleWorktreeRows(worktrees: List<LocalWorktreeUiState>): List<VisibleWorktreeRow> {
    val visibleRows = if (worktrees.hasValidParentBranchNesting()) {
        worktrees.asNestedRows()
    } else {
        worktrees.map { worktree -> VisibleWorktreeRow(worktree = worktree, nestingDepth = 0) }
    }
    return visibleRows
}

private fun List<LocalWorktreeUiState>.hasValidParentBranchNesting(): Boolean {
    val parentBranches = mapNotNull { it.parentBranch }
    val branchCounts = groupingBy { it.branch }.eachCount()
    val worktreeByBranch = associateBy { it.branch }

    return parentBranches.isNotEmpty() &&
        parentBranches.all { branchCounts[it] == 1 } &&
        none { it.hasParentBranchCycle(worktreeByBranch) }
}

private fun LocalWorktreeUiState.hasParentBranchCycle(
    worktreeByBranch: Map<String, LocalWorktreeUiState>,
): Boolean {
    val visitedBranches = mutableSetOf(branch)
    var nextParentBranch = parentBranch
    while (nextParentBranch != null) {
        if (!visitedBranches.add(nextParentBranch)) return true
        nextParentBranch = worktreeByBranch[nextParentBranch]?.parentBranch
    }
    return false
}

private fun List<LocalWorktreeUiState>.asNestedRows(): List<VisibleWorktreeRow> {
    val childrenByParentBranch = filter { it.parentBranch != null }.groupBy { it.parentBranch }
    return buildList {
        this@asNestedRows.filter { it.parentBranch == null }.forEach { worktree ->
            addNestedWorktreeRows(
                worktree = worktree,
                nestingDepth = 0,
                childrenByParentBranch = childrenByParentBranch,
            )
        }
    }
}

private fun MutableList<VisibleWorktreeRow>.addNestedWorktreeRows(
    worktree: LocalWorktreeUiState,
    nestingDepth: Int,
    childrenByParentBranch: Map<String?, List<LocalWorktreeUiState>>,
) {
    add(VisibleWorktreeRow(worktree = worktree, nestingDepth = nestingDepth))
    childrenByParentBranch[worktree.branch].orEmpty().forEach { child ->
        addNestedWorktreeRows(
            worktree = child,
            nestingDepth = nestingDepth + 1,
            childrenByParentBranch = childrenByParentBranch,
        )
    }
}
