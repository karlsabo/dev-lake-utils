package com.github.karlsabo.devlake.enghub.component

internal fun existingWorktreeResults(
    discovery: ExistingBranchDiscoveryUiState,
    query: String,
): List<ExistingWorktreeResult> = buildList {
    addAll(
        filterExistingBranches(discovery.branches, query).map { branch ->
            ExistingBranchWorktreeResult(discovery.repoRootPath, branch)
        },
    )
    discovery.pullRequest
        ?.takeIf { discovery.pullRequestQuery == query.trim() }
        ?.takeIf { pullRequest -> discovery.canUsePullRequestHead(pullRequest.branch) }
        ?.let(::add)
}.rankedByExistingWorktreeMatch(query)

internal fun filterExistingBranches(branches: List<String>, query: String): List<String> = branches
    .mapNotNull { branch ->
        fuzzyMatchRank(query, existingBranchSearchValues(branch))?.let { rank ->
            RankedExistingBranch(branch, rank)
        }
    }
    .sortedWith(existingBranchComparator())
    .map(RankedExistingBranch::branch)

private fun ExistingBranchDiscoveryUiState.canUsePullRequestHead(
    branch: String,
): Boolean = when (originBranchRefreshSucceeded) {
    true -> branch in originBranches
    false -> true
    null -> false
}

private fun List<ExistingWorktreeResult>.rankedByExistingWorktreeMatch(
    query: String,
): List<ExistingWorktreeResult> = mapNotNull { result ->
    rankExistingWorktreeResult(result, query)?.let { rank ->
        RankedExistingWorktreeResult(result, rank)
    }
}.sortedWith(existingWorktreeResultComparator())
    .map(RankedExistingWorktreeResult::result)

private fun rankExistingWorktreeResult(
    result: ExistingWorktreeResult,
    query: String,
): FuzzyMatchRank? = when (result) {
    is ExistingBranchWorktreeResult -> fuzzyMatchRank(query, existingBranchSearchValues(result.branch))
    is ExistingPullRequestWorktreeResult -> fuzzyMatchRank(query, existingPullRequestSearchValues(result))
}

private fun existingBranchSearchValues(branch: String): List<String> = listOf(branch) + branch.split('/')

private fun existingPullRequestSearchValues(result: ExistingPullRequestWorktreeResult): List<String> = listOf(
    "#${result.number}",
    result.number.toString(),
    result.branch,
    result.repositoryFullName,
)

private fun existingBranchComparator(): Comparator<RankedExistingBranch> = compareBy(
    { it.rank.kind },
    { it.rank.distance },
    { it.branch },
)

private fun existingWorktreeResultComparator(): Comparator<RankedExistingWorktreeResult> = compareBy(
    { it.rank.kind },
    { it.rank.distance },
    { it.result.typeSortRank() },
    { it.result.repoRootPath.trimEnd('/', '\\') },
    { existingWorktreeResultLabel(it.result) },
)

private fun ExistingWorktreeResult.typeSortRank(): Int = when (this) {
    is ExistingBranchWorktreeResult -> 0
    is ExistingPullRequestWorktreeResult -> 1
}

private data class RankedExistingBranch(
    val branch: String,
    val rank: FuzzyMatchRank,
)

private data class RankedExistingWorktreeResult(
    val result: ExistingWorktreeResult,
    val rank: FuzzyMatchRank,
)
