package com.github.karlsabo.devlake.enghub.component

internal fun globalExistingWorktreeResults(
    discovery: GlobalExistingBranchDiscoveryUiState,
    query: String,
): List<ExistingWorktreeResult> = discovery.repositories
    .flatMap { repositoryDiscovery -> existingWorktreeResults(repositoryDiscovery, query) }
    .rankedByExistingWorktreeMatch(query)
