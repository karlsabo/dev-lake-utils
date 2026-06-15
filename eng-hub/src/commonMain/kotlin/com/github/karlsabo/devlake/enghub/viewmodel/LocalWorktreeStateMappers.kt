package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CancellationException

internal fun GitWorktreeApi.listLocalWorktreeUiStates(
    repoRootPath: String,
): List<LocalWorktreeUiState> = toLocalWorktreeUiStates(
    repoRootPath = repoRootPath,
    worktrees = listWorktrees(repoRootPath),
)

internal fun GitWorktreeApi.toLocalWorktreeUiStates(
    repoRootPath: String,
    worktrees: List<Worktree>,
): List<LocalWorktreeUiState> {
    val parentBranchesByChildBranch = inferWorktreeParentBranchesBestEffort(repoRootPath)
    return worktrees.toLocalWorktreeUiStates(
        repositoryRootPath = repoRootPath,
        parentBranchesByChildBranch = parentBranchesByChildBranch,
        needsRebaseByChildBranch = rebaseNeedsByChildBranchBestEffort(repoRootPath, parentBranchesByChildBranch),
    )
}

private fun GitWorktreeApi.inferWorktreeParentBranchesBestEffort(repoRootPath: String): Map<String, String> = try {
    inferWorktreeParentBranches(repoRootPath)
} catch (e: CancellationException) {
    throw e
} catch (_: RuntimeException) {
    emptyMap()
}

private fun GitWorktreeApi.rebaseNeedsByChildBranchBestEffort(
    repoRootPath: String,
    parentBranchesByChildBranch: Map<String, String>,
): Map<String, Boolean> = parentBranchesByChildBranch.mapValues { (childBranch, parentBranch) ->
    branchNeedsRebaseBestEffort(repoRootPath, parentBranch, childBranch)
}

private fun GitWorktreeApi.branchNeedsRebaseBestEffort(
    repoRootPath: String,
    parentBranch: String,
    childBranch: String,
): Boolean = try {
    branchNeedsRebase(repoRootPath, parentBranch, childBranch)
} catch (e: CancellationException) {
    throw e
} catch (_: RuntimeException) {
    false
}
