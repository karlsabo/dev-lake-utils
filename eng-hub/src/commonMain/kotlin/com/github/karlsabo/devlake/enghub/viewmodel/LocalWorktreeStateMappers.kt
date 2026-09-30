package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.Worktree

private const val DETACHED_BRANCH = "(detached)"

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
            val parentBranch = enrichedWorktree.parentBranch?.takeIf { it in visibleBranches }
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
): List<LocalWorktreeUiState> {
    val parentOutcomes = inferWorktreeParentBranchOutcomes(repoRootPath)
    val needsRebaseByChildBranch = rebaseNeedsByChildBranch(repoRootPath, parentOutcomes.parents)
    val originLookup = lookupOriginDefaultBranch(repoRootPath)
    val originDefaultBranch = originLookup.getOrNull()
    val visibleBranches = worktrees.mapTo(mutableSetOf()) { it.branch }
    return worktrees.map { worktree ->
        val canUpdateFromOrigin = if (originLookup.isFailure) {
            worktree.canUpdateFromOrigin
        } else {
            worktree.branch == originDefaultBranch
        }
        val parentUnresolved = worktree.branch in parentOutcomes.unresolvedBranches
        val inferredParent = parentOutcomes.parents[worktree.branch]
        val parentBranch = (if (parentUnresolved) worktree.parentBranch else inferredParent)
            ?.takeIf { !canUpdateFromOrigin && it in visibleBranches }
        val retainedTarget = worktree.integrationTargetBranch.takeIf {
            originLookup.isFailure && worktree.parentBranch == null
        }
        val integrationTargetBranch = parentBranch ?: originDefaultBranch?.takeIf {
            it.isNotBlank() && worktree.branch != DETACHED_BRANCH && !canUpdateFromOrigin
        } ?: retainedTarget
        val needsRebase = if (parentUnresolved) {
            worktree.needsRebase
        } else {
            needsRebaseByChildBranch[worktree.branch] == true
        }
        worktree.copy(
            parentBranch = parentBranch,
            needsRebase = parentBranch != null && needsRebase,
            canUpdateFromOrigin = canUpdateFromOrigin,
            integrationTargetBranch = integrationTargetBranch,
        )
    }
}

private fun GitWorktreeApi.rebaseNeedsByChildBranch(
    repoRootPath: String,
    parentBranchesByChildBranch: Map<String, String>,
): Map<String, Boolean> = parentBranchesByChildBranch.mapValues { (childBranch, parentBranch) ->
    branchNeedsRebase(repoRootPath, parentBranch, childBranch)
}
