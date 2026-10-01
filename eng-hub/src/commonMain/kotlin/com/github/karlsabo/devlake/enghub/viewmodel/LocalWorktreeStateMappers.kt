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
    preserveCheckout: Boolean = false,
): List<LocalWorktreeUiState> {
    val visibleBranches = mapTo(mutableSetOf()) { it.branch }
    val enrichmentByPath = enrichedWorktrees.associateBy { it.path.normalizedRepositoryPath() }
    return map { currentWorktree ->
        val enrichedWorktree = enrichmentByPath[currentWorktree.path.normalizedRepositoryPath()]
            ?.takeIf { it.branch == currentWorktree.branch && it.baseCommitHash == currentWorktree.baseCommitHash }
        if (enrichedWorktree == null) {
            currentWorktree
        } else {
            val parentBranch = enrichedWorktree.parentBranch?.takeIf {
                it in visibleBranches && !enrichedWorktree.canUpdateFromOrigin
            }
            currentWorktree.copy(
                parentBranch = parentBranch,
                needsRebase = parentBranch != null && enrichedWorktree.needsRebase,
                canUpdateFromOrigin = enrichedWorktree.canUpdateFromOrigin,
                integrationTargetBranch = if (enrichedWorktree.parentBranch != null) {
                    parentBranch
                } else {
                    enrichedWorktree.integrationTargetBranch
                },
                checkout = if (preserveCheckout) enrichedWorktree.checkout else currentWorktree.checkout,
            )
        }
    }
}

/** Fills the dirty status of the row identified by normalized path plus branch; other rows are unchanged. */
internal fun List<LocalWorktreeUiState>.withWorktreeStatus(
    normalizedWorktreePath: String,
    branch: String,
    isDirty: Boolean,
): List<LocalWorktreeUiState> = map { worktree ->
    if (worktree.path.normalizedRepositoryPath() == normalizedWorktreePath && worktree.branch == branch) {
        worktree.copy(isDirty = isDirty)
    } else {
        worktree
    }
}

internal fun GitWorktreeApi.enrichLocalWorktreeUiStates(
    repoRootPath: String,
    worktrees: List<LocalWorktreeUiState>,
): List<LocalWorktreeUiState> = lookupLocalWorktreeEnrichment(repoRootPath, worktrees).worktrees

internal fun GitWorktreeApi.lookupLocalWorktreeEnrichment(
    repoRootPath: String,
    worktrees: List<LocalWorktreeUiState>,
): LocalWorktreeEnrichment {
    val parentOutcomes = inferWorktreeParentBranchOutcomes(repoRootPath)
    return LocalWorktreeEnrichment(
        worktrees,
        parentOutcomes,
        lookupOriginDefaultBranch(repoRootPath),
        parentOutcomes.parents.mapValues { (childBranch, parentBranch) ->
            branchNeedsRebase(repoRootPath, parentBranch, childBranch)
        },
    )
}
