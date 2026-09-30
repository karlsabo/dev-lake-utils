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
    unresolvedOrigin: Boolean = false,
    unresolvedParents: Set<String> = emptySet(),
): List<LocalWorktreeUiState> {
    val visibleBranches = mapTo(mutableSetOf()) { it.branch }
    val enrichmentByPath = enrichedWorktrees.associateBy { it.path.normalizedRepositoryPath() }
    return map { currentWorktree ->
        val enrichedWorktree = enrichmentByPath[currentWorktree.path.normalizedRepositoryPath()]
            ?.takeIf { it.branch == currentWorktree.branch && it.baseCommitHash == currentWorktree.baseCommitHash }
        if (enrichedWorktree == null) {
            currentWorktree
        } else {
            val resolvedWorktree = enrichedWorktree.copy(
                parentBranch = if (currentWorktree.branch in unresolvedParents) {
                    currentWorktree.parentBranch
                } else {
                    enrichedWorktree.parentBranch
                },
                needsRebase = if (currentWorktree.branch in unresolvedParents) {
                    currentWorktree.needsRebase
                } else {
                    enrichedWorktree.needsRebase
                },
                canUpdateFromOrigin = if (unresolvedOrigin) {
                    currentWorktree.canUpdateFromOrigin
                } else {
                    enrichedWorktree.canUpdateFromOrigin
                },
            )
            val parentBranch = resolvedWorktree.parentBranch?.takeIf {
                it in visibleBranches && !resolvedWorktree.canUpdateFromOrigin
            }
            currentWorktree.copy(
                parentBranch = parentBranch,
                needsRebase = parentBranch != null && resolvedWorktree.needsRebase,
                canUpdateFromOrigin = resolvedWorktree.canUpdateFromOrigin,
                integrationTargetBranch = if (resolvedWorktree.parentBranch != null) {
                    parentBranch
                } else if (unresolvedOrigin) {
                    currentWorktree.integrationTargetBranch.takeIf { currentWorktree.parentBranch == null }
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

internal class LocalWorktreeEnrichment(
    val worktrees: List<LocalWorktreeUiState>,
    private val unresolvedOrigin: Boolean = false,
    private val unresolvedParents: Set<String> = emptySet(),
) {
    // Resolve fallbacks inside the atomic publication, not against the queued snapshot.
    fun mergeInto(current: List<LocalWorktreeUiState>): List<LocalWorktreeUiState> = current.withEnrichmentFrom(
        worktrees,
        unresolvedOrigin = unresolvedOrigin,
        unresolvedParents = unresolvedParents,
    )

    fun retaining(worktrees: List<LocalWorktreeUiState>): LocalWorktreeEnrichment = LocalWorktreeEnrichment(
        worktrees,
        unresolvedOrigin,
        unresolvedParents,
    )
}

internal fun GitWorktreeApi.lookupLocalWorktreeEnrichment(
    repoRootPath: String,
    worktrees: List<LocalWorktreeUiState>,
): LocalWorktreeEnrichment {
    val parentOutcomes = inferWorktreeParentBranchOutcomes(repoRootPath)
    val needsRebaseByChildBranch = rebaseNeedsByChildBranch(repoRootPath, parentOutcomes.parents)
    val originLookup = lookupOriginDefaultBranch(repoRootPath)
    val originDefaultBranch = originLookup.getOrNull()
    val visibleBranches = worktrees.mapTo(mutableSetOf()) { it.branch }
    val enrichedWorktrees = worktrees.map { worktree ->
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
    return LocalWorktreeEnrichment(enrichedWorktrees, originLookup.isFailure, parentOutcomes.unresolvedBranches)
}

private fun GitWorktreeApi.rebaseNeedsByChildBranch(
    repoRootPath: String,
    parentBranchesByChildBranch: Map<String, String>,
): Map<String, Boolean> = parentBranchesByChildBranch.mapValues { (childBranch, parentBranch) ->
    branchNeedsRebase(repoRootPath, parentBranch, childBranch)
}
