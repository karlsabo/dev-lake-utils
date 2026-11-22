@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.PullRequestProvider
import com.github.karlsabo.devlake.enghub.state.PullRequestProviderFailure
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestsResult
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus.PENDING
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubPullRequestReviewApi
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.github.GitHubPullRequestSummaryApi
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.github.pullRequestDetailsUrl
import com.github.karlsabo.gitlab.GitLabMergeRequestApi
import com.github.karlsabo.gitlab.GitLabRestApi
import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds

internal const val POLLING_RETRY_DELAY_MS = 5_000L
internal const val STATE_FLOW_STOP_TIMEOUT_MS = 5_000L
private const val ZERO_CHECK_RUNS = 0

internal data class CommittedGitLabAccess(
    val apiConfig: GitLabApiRestConfig? = null,
    val createApi: (GitLabApiRestConfig) -> GitLabMergeRequestApi = { GitLabRestApi(it) },
) {
    val isReady: Boolean
        get() = apiConfig != null
}

private data class PullRequestPollingInputs(
    val config: EngHubConfig,
    val gitHubAccess: CommittedGitHubAccess,
    val gitLabAccess: CommittedGitLabAccess,
)

internal fun ViewModel.pullRequestsStateFlow(
    gitHubAccess: StateFlow<CommittedGitHubAccess>,
    gitLabAccess: StateFlow<CommittedGitLabAccess>,
    configs: StateFlow<EngHubConfig>,
): StateFlow<PullRequestsResult?> = combine(
    configs,
    gitHubAccess,
    gitLabAccess,
) { config, gitHub, gitLab -> PullRequestPollingInputs(config, gitHub, gitLab) }
    .flatMapLatest { inputs ->
        combine(
            gitHubPullRequestsFlow(inputs.config, inputs.gitHubAccess),
            gitLabPullRequestsFlow(inputs.config, inputs.gitLabAccess),
        ) { gitHubResult, gitLabResult -> mergePullRequestResults(gitHubResult, gitLabResult) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS), null)

private fun gitHubPullRequestsFlow(
    config: EngHubConfig,
    access: CommittedGitHubAccess,
): Flow<Result<List<PullRequestUiState>>?> = if (!access.isReady) {
    flowOf(null)
} else {
    pullRequestsForConfig(
        searchApi = access.services.pullRequestSearchApi,
        reviewApi = access.services.pullRequestReviewApi,
        summaryApi = access.services.pullRequestSummaryApi,
        config = config,
    ).clearBeforeFirstEmission()
}

private fun gitLabPullRequestsFlow(
    config: EngHubConfig,
    access: CommittedGitLabAccess,
): Flow<Result<List<PullRequestUiState>>?> {
    val apiConfig = access.apiConfig
    if (!access.isReady || apiConfig == null) return flowOf(null)
    return gitLabPullRequestsForConfig(config, access.createApi, apiConfig).clearBeforeFirstEmission()
}

internal fun mergePullRequestResults(
    gitHubResult: Result<List<PullRequestUiState>>?,
    gitLabResult: Result<List<PullRequestUiState>>?,
): PullRequestsResult? {
    val providerResults = listOfNotNull(
        gitHubResult?.toProviderResult(PullRequestProvider.GitHub),
        gitLabResult?.toProviderResult(PullRequestProvider.GitLab),
    )
    if (providerResults.isEmpty()) return null
    return PullRequestsResult(
        pullRequests = providerResults.flatMap(ProviderResult::pullRequests),
        failures = providerResults.mapNotNull(ProviderResult::failure),
    )
}

private data class ProviderResult(
    val pullRequests: List<PullRequestUiState>,
    val failure: PullRequestProviderFailure?,
)

private fun Result<List<PullRequestUiState>>.toProviderResult(
    provider: PullRequestProvider,
): ProviderResult = ProviderResult(
    pullRequests = getOrNull().orEmpty(),
    failure = exceptionOrNull()?.let { error ->
        PullRequestProviderFailure(provider = provider, message = error.message ?: "Unknown error")
    },
)

internal fun gitLabPullRequestsForConfig(
    config: EngHubConfig,
    createApi: (GitLabApiRestConfig) -> GitLabMergeRequestApi,
    apiConfig: GitLabApiRestConfig,
): Flow<Result<List<PullRequestUiState>>> = flow {
    val api = createApi(apiConfig)
    try {
        while (true) {
            val result = runCatching {
                api.getAuthoredOpenMergeRequests().map { mergeRequest -> mergeRequest.toPullRequestUiState() }
            }.rethrowCancellation()
            result.onFailure { logger.error(it) { "Error polling GitLab merge requests" } }
            emit(result)
            delay((if (result.isSuccess) config.pollIntervalMs else POLLING_RETRY_DELAY_MS).milliseconds)
        }
    } finally {
        api.close()
    }
}.flowOn(Dispatchers.IO)

internal fun pullRequestsForConfig(
    searchApi: GitHubPullRequestSearchApi,
    reviewApi: GitHubPullRequestReviewApi,
    summaryApi: GitHubPullRequestSummaryApi,
    config: EngHubConfig,
): Flow<Result<List<PullRequestUiState>>> = if (config.organizationIds.isEmpty() || config.gitHubAuthor.isBlank()) {
    flowOf(Result.success(emptyList()))
} else {
    flow {
        var hasEmittedResult = false
        while (true) {
            val result = runCatching {
                val issues = searchApi.getOpenPullRequestsByAuthor(config.organizationIds, config.gitHubAuthor)
                buildPullRequestUiStates(issues, reviewApi, summaryApi)
            }.rethrowCancellation()
            result.onFailure { logger.error(it) { "Error polling pull requests" } }
            if (result.isSuccess || !hasEmittedResult) {
                emit(result)
                hasEmittedResult = true
            }
            delay((if (result.isSuccess) config.pollIntervalMs else POLLING_RETRY_DELAY_MS).milliseconds)
        }
    }
}.flowOn(Dispatchers.IO)

internal suspend fun GitHubApi.buildPullRequestUiStates(
    issues: List<Issue>,
): List<PullRequestUiState> = buildPullRequestUiStates(issues, this, this)

internal suspend fun buildPullRequestUiStates(
    issues: List<Issue>,
    reviewApi: GitHubPullRequestReviewApi,
    summaryApi: GitHubPullRequestSummaryApi,
): List<PullRequestUiState> = issues.sortedBy { it.createdAt }.map { issue ->
    val pr = reviewApi.getPullRequestByUrl(issue.pullRequestDetailsUrl)
    val headRef = pr.head?.ref
    val headSha = pr.head?.sha
    val repoUrl = issue.repositoryUrl ?: ""
    val (owner, repo) = if (repoUrl.isNotEmpty()) extractOwnerAndRepo(repoUrl) else ("" to "")
    val checkRunSummary = if (headSha != null && owner.isNotEmpty()) {
        summaryApi.getCheckRunsForRef(owner, repo, headSha)
    } else {
        CheckRunSummary(ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, PENDING)
    }
    val reviewSummary = if (owner.isNotEmpty()) {
        summaryApi.getReviewSummary(owner, repo, issue.number)
    } else {
        ReviewSummary(ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, emptyList())
    }
    issue.toPullRequestUiState(checkRunSummary, reviewSummary, headRef)
}
