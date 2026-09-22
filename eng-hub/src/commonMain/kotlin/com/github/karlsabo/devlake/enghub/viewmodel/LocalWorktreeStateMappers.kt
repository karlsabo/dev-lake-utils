package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
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
    val enrichmentByPath = enrichedWorktrees.associateBy { it.path.normalizedRepositoryPath() }
    return map { currentWorktree ->
        val enrichedWorktree = enrichmentByPath[currentWorktree.path.normalizedRepositoryPath()]
            ?.takeIf { it.branch == currentWorktree.branch }
        if (enrichedWorktree == null) {
            currentWorktree
        } else {
            val parentBranch = enrichedWorktree.parentBranch?.takeIf { it in visibleBranches }
            currentWorktree.copy(
                parentBranch = parentBranch,
                needsRebase = parentBranch != null && enrichedWorktree.needsRebase,
                canUpdateFromOrigin = enrichedWorktree.canUpdateFromOrigin,
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
    val originDefaultBranch = inferOriginDefaultBranch(repoRootPath)
    val visibleBranches = worktrees.mapTo(mutableSetOf()) { it.branch }
    return worktrees.map { worktree ->
        val canUpdateFromOrigin = worktree.branch == originDefaultBranch
        val parentBranch = parentBranchesByChildBranch[worktree.branch]
            ?.takeIf { !canUpdateFromOrigin && it in visibleBranches }
        worktree.copy(
            parentBranch = parentBranch,
            needsRebase = parentBranch != null && needsRebaseByChildBranch[worktree.branch] == true,
            canUpdateFromOrigin = canUpdateFromOrigin,
        )
    }
}

private fun GitWorktreeApi.rebaseNeedsByChildBranch(
    repoRootPath: String,
    parentBranchesByChildBranch: Map<String, String>,
): Map<String, Boolean> = parentBranchesByChildBranch.mapValues { (childBranch, parentBranch) ->
    branchNeedsRebase(repoRootPath, parentBranch, childBranch)
}
