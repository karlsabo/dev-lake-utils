package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.github.GitHubRepositoryIdentity
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.parseGitHubRepositoryIdentity
import kotlinx.coroutines.Job

internal class RepositoryPullRequestWorktreeDiscovery(
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val launchGitHubAction: (suspend (EngHubGitHubServices) -> Unit) -> Job,
) {
    private var discoveryJob: Job? = null

    fun discover(repoRootPath: String, query: String) {
        if (repoRootPath.isBlank()) return
        discoveryJob?.cancel()
        val normalizedQuery = query.trim()
        val number = plainPullRequestNumber(normalizedQuery)
        if (number == null) {
            clear(repoRootPath, normalizedQuery)
            return
        }

        val request = start(repoRootPath, normalizedQuery)
        discoveryJob = launchGitHubAction { services ->
            runCatching {
                discoverPullRequestWorktreeCandidate(
                    repoRootPath = repoRootPath,
                    number = number,
                    gitWorktreeApi = gitWorktreeApi,
                    services = services,
                )
            }
                .rethrowCancellation()
                .onSuccess { outcome -> finish(request, outcome) }
                .onFailure { failure ->
                    logger.error(failure) { "Failed to discover pull request #$number for $repoRootPath" }
                    finish(request, PullRequestDiscoveryOutcome.NoResult)
                }
        }
    }

    private fun start(repoRootPath: String, query: String): ExistingBranchDiscoveryState {
        while (true) {
            val current = state.existingBranchDiscovery.value
            val base = current.takeIf { it.repoRootPath == repoRootPath }
                ?: ExistingBranchDiscoveryState(repoRootPath = repoRootPath)
            val request = base.copy(
                pullRequestQuery = query,
                pullRequest = null,
                isPullRequestLoading = true,
                pullRequestRequestId = current.pullRequestRequestId + 1,
                unsupportedPullRequestMessage = null,
            )
            if (state.existingBranchDiscovery.compareAndSet(current, request)) return request
        }
    }

    private fun clear(repoRootPath: String, query: String) {
        while (true) {
            val current = state.existingBranchDiscovery.value
            if (current.repoRootPath != repoRootPath) return
            val cleared = current.copy(
                pullRequestQuery = query,
                pullRequest = null,
                isPullRequestLoading = false,
                pullRequestRequestId = current.pullRequestRequestId + 1,
                unsupportedPullRequestMessage = null,
            )
            if (state.existingBranchDiscovery.compareAndSet(current, cleared)) return
        }
    }

    private fun finish(
        request: ExistingBranchDiscoveryState,
        outcome: PullRequestDiscoveryOutcome,
    ) {
        while (true) {
            val current = state.existingBranchDiscovery.value
            if (
                current.repoRootPath != request.repoRootPath ||
                current.pullRequestRequestId != request.pullRequestRequestId
            ) {
                return
            }
            val completed = current.copy(
                pullRequest = (outcome as? PullRequestDiscoveryOutcome.Candidate)?.value,
                isPullRequestLoading = false,
                unsupportedPullRequestMessage = if (outcome == PullRequestDiscoveryOutcome.UnsupportedFork) {
                    "Fork pull requests are not supported."
                } else {
                    null
                },
            )
            if (state.existingBranchDiscovery.compareAndSet(current, completed)) return
        }
    }
}

internal sealed interface PullRequestDiscoveryOutcome {
    data class Candidate(
        val value: ExistingPullRequestWorktreeCandidate,
    ) : PullRequestDiscoveryOutcome

    data object UnsupportedFork : PullRequestDiscoveryOutcome
    data object NoResult : PullRequestDiscoveryOutcome
}

internal fun plainPullRequestNumber(query: String): Int? = query.trim()
    .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

internal suspend fun discoverPullRequestWorktreeCandidate(
    repoRootPath: String,
    number: Int,
    gitWorktreeApi: GitWorktreeApi,
    services: EngHubGitHubServices,
): PullRequestDiscoveryOutcome {
    val identity = gitWorktreeApi.originUrl(repoRootPath)
        ?.let(::parseGitHubRepositoryIdentity)
        ?: return PullRequestDiscoveryOutcome.NoResult
    val pullRequest = services.pullRequestReviewApi.getPullRequest(
        owner = identity.owner,
        repository = identity.repository,
        number = number,
    )
    return pullRequest.toDiscoveryOutcome(identity, number)
}

private fun PullRequest.toDiscoveryOutcome(
    baseRepository: GitHubRepositoryIdentity,
    requestedNumber: Int,
): PullRequestDiscoveryOutcome {
    val headBranch = head?.ref?.takeIf(String::isNotBlank)
    val headRepository = head?.repo?.fullName?.takeIf(String::isNotBlank)
    return when {
        headBranch == null || headRepository == null -> PullRequestDiscoveryOutcome.NoResult

        !baseRepository.matches(headRepository) -> PullRequestDiscoveryOutcome.UnsupportedFork

        else -> PullRequestDiscoveryOutcome.Candidate(
            ExistingPullRequestWorktreeCandidate(
                branch = headBranch,
                repositoryFullName = baseRepository.fullName,
                number = number ?: requestedNumber,
            ),
        )
    }
}
