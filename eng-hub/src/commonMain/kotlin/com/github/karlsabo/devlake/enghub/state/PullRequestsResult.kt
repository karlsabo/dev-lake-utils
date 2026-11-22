package com.github.karlsabo.devlake.enghub.state

/**
 * A failed provider poll kept alongside the items that the other providers returned, so a single provider failure
 * stays visible instead of being hidden by a healthy provider.
 */
data class PullRequestProviderFailure(
    val provider: PullRequestProvider,
    val message: String,
)

data class PullRequestsResult(
    val pullRequests: List<PullRequestUiState>,
    val failures: List<PullRequestProviderFailure> = emptyList(),
)
