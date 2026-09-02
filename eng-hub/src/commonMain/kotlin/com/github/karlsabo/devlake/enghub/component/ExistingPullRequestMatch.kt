package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.github.parseGitHubPullRequestReference

internal fun pullRequestMatchRank(
    result: ExistingPullRequestWorktreeResult,
    query: String,
): FuzzyMatchRank? {
    val numberQuery = parseGitHubPullRequestReference(query)?.number?.toString() ?: return null
    return fuzzyMatchRank(
        numberQuery,
        listOf(
            "#${result.number}",
            result.number.toString(),
            result.branch,
            result.repositoryFullName,
        ),
    )
}
