package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

internal fun List<LocalRepositoryUiState>.withPreservedWorktrees(
    previousRepositories: List<LocalRepositoryUiState>,
    updatedRootPath: String,
    updatedWorktrees: List<LocalWorktreeUiState>,
    expandUpdatedRepository: Boolean = false,
): List<LocalRepositoryUiState> {
    val normalizedUpdatedRootPath = updatedRootPath.normalizedRepoPath()
    val previousRepositoriesByPath = previousRepositories.associateBy { repository ->
        repository.path.normalizedRepoPath()
    }

    return map { repository ->
        val normalizedPath = repository.path.normalizedRepoPath()
        val previousRepository = previousRepositoriesByPath[normalizedPath]
        val worktrees = if (normalizedPath == normalizedUpdatedRootPath) {
            updatedWorktrees
        } else {
            previousRepository?.worktrees.orEmpty()
        }
        val isExpanded = if (normalizedPath == normalizedUpdatedRootPath && expandUpdatedRepository) {
            true
        } else {
            previousRepository?.isExpanded ?: repository.isExpanded
        }
        repository.copy(isExpanded = isExpanded, worktrees = worktrees)
    }
}
