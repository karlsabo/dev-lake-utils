package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.Worktree

internal fun GitWorktreeApi.listLocalWorktreeUiStates(
    repoRootPath: String,
): List<LocalWorktreeUiState> = toLocalWorktreeUiStates(
    repoRootPath = repoRootPath,
    worktrees = listWorktrees(repoRootPath),
)

internal fun GitWorktreeApi.toLocalWorktreeUiStates(
    repoRootPath: String,
    worktrees: List<Worktree>,
): List<LocalWorktreeUiState> = worktrees.toLocalWorktreeUiStates(
    repositoryRootPath = repoRootPath,
    parentBranchesByChildBranch = inferWorktreeParentBranchesBestEffort(repoRootPath),
)

private fun GitWorktreeApi.inferWorktreeParentBranchesBestEffort(repoRootPath: String): Map<String, String> = try {
    inferWorktreeParentBranches(repoRootPath)
} catch (_: RuntimeException) {
    emptyMap()
}
