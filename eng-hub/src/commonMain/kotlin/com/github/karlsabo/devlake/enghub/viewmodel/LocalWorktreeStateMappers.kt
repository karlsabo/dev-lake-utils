package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.Worktree

internal fun GitWorktreeApi.toLocalWorktreeUiStates(
    repoRootPath: String,
    worktrees: List<Worktree>,
): List<LocalWorktreeUiState> = enrichLocalWorktreeUiStates(
    repoRootPath = repoRootPath,
    worktrees = worktrees.toLocalWorktreeUiStates(repoRootPath),
)

internal fun List<LocalWorktreeUiState>.withEnrichmentFrom(
    enrichedWorktrees: List<LocalWorktreeUiState>,
): List<LocalWorktreeUiState> {
    val visibleBranches = mapTo(mutableSetOf()) { it.branch }
    val enrichmentByPath = enrichedWorktrees.associateBy { it.path.normalizedRepoPath() }
    return map { currentWorktree ->
        val enrichedWorktree = enrichmentByPath[currentWorktree.path.normalizedRepoPath()]
            ?.takeIf { it.branch == currentWorktree.branch }
        if (enrichedWorktree == null) {
            currentWorktree
        } else {
            val parentBranch = enrichedWorktree.parentBranch?.takeIf { it in visibleBranches }
            currentWorktree.copy(
                parentBranch = parentBranch,
                needsRebase = parentBranch != null && enrichedWorktree.needsRebase,
            )
        }
    }
}

internal fun GitWorktreeApi.enrichLocalWorktreeUiStates(
    repoRootPath: String,
    worktrees: List<LocalWorktreeUiState>,
): List<LocalWorktreeUiState> {
    val parentBranchesByChildBranch = inferWorktreeParentBranches(repoRootPath)
    val needsRebaseByChildBranch = rebaseNeedsByChildBranch(repoRootPath, parentBranchesByChildBranch)
    val visibleBranches = worktrees.mapTo(mutableSetOf()) { it.branch }
    return worktrees.map { worktree ->
        worktree.copy(
            parentBranch = parentBranchesByChildBranch[worktree.branch]?.takeIf { it in visibleBranches },
            needsRebase = needsRebaseByChildBranch[worktree.branch] == true,
        )
    }
}

private fun GitWorktreeApi.rebaseNeedsByChildBranch(
    repoRootPath: String,
    parentBranchesByChildBranch: Map<String, String>,
): Map<String, Boolean> = parentBranchesByChildBranch.mapValues { (childBranch, parentBranch) ->
    branchNeedsRebase(repoRootPath, parentBranch, childBranch)
}
