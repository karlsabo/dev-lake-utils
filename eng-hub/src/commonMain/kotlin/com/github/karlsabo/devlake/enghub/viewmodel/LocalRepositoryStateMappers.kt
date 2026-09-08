package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.github.GitHubRepositoryIdentity

internal fun List<LocalRepositoryUiState>.withPreservedWorktrees(
    previousRepositories: List<LocalRepositoryUiState>,
    updatedRootPath: String,
    updatedWorktrees: List<LocalWorktreeUiState>,
    expandUpdatedRepository: Boolean = false,
): List<LocalRepositoryUiState> {
    val normalizedUpdatedRootPath = updatedRootPath.normalizedRepositoryPath()
    val previousRepositoriesByPath = previousRepositories.associateBy { repository ->
        repository.path.normalizedRepositoryPath()
    }

    return map { repository ->
        val normalizedPath = repository.path.normalizedRepositoryPath()
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
        repository.copy(
            isExpanded = isExpanded,
            repositoryIdentity = previousRepository?.repositoryIdentity,
            worktrees = worktrees,
        )
    }
}

internal fun connectedPullRequest(
    repositoryIdentity: GitHubRepositoryIdentity?,
    branch: String,
    pullRequests: List<PullRequestUiState>,
): PullRequestUiState? = repositoryIdentity?.let { identity ->
    pullRequests
        .asSequence()
        .filter { pullRequest ->
            identity.matches(pullRequest.repositoryFullName) && pullRequest.headRef == branch
        }
        .maxByOrNull(PullRequestUiState::number)
}
