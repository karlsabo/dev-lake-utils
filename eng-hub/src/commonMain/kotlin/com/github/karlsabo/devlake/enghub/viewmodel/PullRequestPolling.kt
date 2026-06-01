@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus.PENDING
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.github.pullRequestDetailsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds

private const val POLLING_RETRY_COUNT = 5L
internal const val POLLING_RETRY_DELAY_MS = 5_000L
internal const val STATE_FLOW_STOP_TIMEOUT_MS = 5_000L
private const val ZERO_CHECK_RUNS = 0

internal fun ViewModel.pullRequestsStateFlow(
    gitHubApi: GitHubApi,
    config: EngHubConfig,
): StateFlow<Result<List<PullRequestUiState>>?> = flow {
    while (true) {
        val issues = gitHubApi.getOpenPullRequestsByAuthor(config.organizationIds, config.gitHubAuthor)
        val prStates = gitHubApi.buildPullRequestUiStates(issues)
        emit(Result.success(prStates))
        delay(config.pollIntervalMs.milliseconds)
    }
}
    .flowOn(Dispatchers.IO)
    .retry(POLLING_RETRY_COUNT) { cause ->
        if (cause.isRetriablePollingFailure()) {
            delay(POLLING_RETRY_DELAY_MS.milliseconds)
            true
        } else {
            false
        }
    }
    .catch { e ->
        logger.error(e) { "Error polling pull requests" }
        emit(Result.failure(e))
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS), null)

internal suspend fun GitHubApi.buildPullRequestUiStates(
    issues: List<Issue>,
): List<PullRequestUiState> = issues.map { issue ->
    val pr = getPullRequestByUrl(issue.pullRequestDetailsUrl)
    val headRef = pr.head?.ref
    val headSha = pr.head?.sha
    val repoUrl = issue.repositoryUrl ?: ""
    val (owner, repo) = if (repoUrl.isNotEmpty()) extractOwnerAndRepo(repoUrl) else ("" to "")
    val checkRunSummary = if (headSha != null && owner.isNotEmpty()) {
        getCheckRunsForRef(owner, repo, headSha)
    } else {
        CheckRunSummary(ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, PENDING)
    }
    val reviewSummary = if (owner.isNotEmpty()) {
        getReviewSummary(owner, repo, issue.number)
    } else {
        ReviewSummary(ZERO_CHECK_RUNS, ZERO_CHECK_RUNS, emptyList())
    }
    issue.toPullRequestUiState(checkRunSummary, reviewSummary, headRef)
}
