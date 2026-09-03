package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.github.GitHubBranchReference
import com.github.karlsabo.github.parseGitHubBranchReference

internal fun globalExistingWorktreeResults(
    discovery: GlobalExistingBranchDiscoveryUiState,
    query: String,
): List<ExistingWorktreeResult> {
    if (query.isBlank()) return emptyList()

    val branchReference = parseGitHubBranchReference(query)
    val branchQuery = branchReference?.branch ?: query
    return discovery.repositories
        .filter { repository -> repository.matches(branchReference) }
        .flatMap { repositoryDiscovery -> existingWorktreeResults(repositoryDiscovery, branchQuery) }
        .rankedByExistingWorktreeMatch(branchQuery)
}

private fun ExistingBranchDiscoveryUiState.matches(reference: GitHubBranchReference?): Boolean = reference == null ||
    repositoryIdentity?.matches(reference.repository.fullName) == true
