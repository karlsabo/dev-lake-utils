package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.component.ExistingBranchDiscoveryUiState
import com.github.karlsabo.devlake.enghub.component.ExistingPullRequestWorktreeResult
import com.github.karlsabo.devlake.enghub.component.GlobalExistingBranchDiscoveryUiState
import com.github.karlsabo.devlake.enghub.viewmodel.ExistingBranchDiscoveryState
import com.github.karlsabo.devlake.enghub.viewmodel.GlobalExistingBranchDiscoveryState

internal fun GlobalExistingBranchDiscoveryState.toUiState() = GlobalExistingBranchDiscoveryUiState(
    repositories = repoRootPaths.mapNotNull(repositories::get).map { it.toUiState() },
    isLoading = isLoading,
)

internal fun ExistingBranchDiscoveryState.toUiState(): ExistingBranchDiscoveryUiState = ExistingBranchDiscoveryUiState(
    repoRootPath = repoRootPath,
    branches = branches,
    originBranches = originBranches,
    originBranchRefreshSucceeded = originBranchRefreshSucceeded,
    isLoading = isLoading,
    pullRequestQuery = pullRequestQuery,
    pullRequest = pullRequest?.let { candidate ->
        ExistingPullRequestWorktreeResult(
            repoRootPath = repoRootPath,
            branch = candidate.branch,
            repositoryFullName = candidate.repositoryFullName,
            number = candidate.number,
        )
    },
    isPullRequestLoading = isPullRequestLoading,
    unsupportedPullRequestMessage = unsupportedPullRequestMessage,
)
