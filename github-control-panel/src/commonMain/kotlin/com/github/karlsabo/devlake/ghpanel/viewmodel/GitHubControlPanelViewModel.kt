package com.github.karlsabo.devlake.ghpanel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.ghpanel.GitHubControlPanelConfig
import com.github.karlsabo.devlake.ghpanel.state.NotificationUiState
import com.github.karlsabo.devlake.ghpanel.state.PullRequestUiState
import com.github.karlsabo.devlake.ghpanel.state.toNotificationUiState
import com.github.karlsabo.devlake.ghpanel.state.toPullRequestUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.system.DesktopLauncher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private val logger = KotlinLogging.logger {}

class GitHubControlPanelViewModel(
    private val gitHubApi: GitHubApi,
    private val gitWorktreeApi: GitWorktreeApi,
    private val desktopLauncher: DesktopLauncher,
    private val config: GitHubControlPanelConfig,
) : ViewModel() {

    val pullRequests: StateFlow<Result<List<PullRequestUiState>>> = flow {
        while (true) {
            val issues = gitHubApi.getOpenPullRequestsByAuthor(config.organizationIds, config.gitHubAuthor)
            val prStates = issues.map { issue ->
                val pr = gitHubApi.getPullRequestByUrl(issue.url ?: issue.htmlUrl)
                val headRef = pr.head?.ref
                val headSha = pr.head?.sha
                val repoUrl = issue.repositoryUrl ?: ""
                val (owner, repo) = if (repoUrl.isNotEmpty()) extractOwnerAndRepo(repoUrl) else ("" to "")
                val checkRunSummary = if (headSha != null && owner.isNotEmpty()) {
                    gitHubApi.getCheckRunsForRef(owner, repo, headSha)
                } else {
                    com.github.karlsabo.github.CheckRunSummary(0, 0, 0, 0, com.github.karlsabo.github.CiStatus.PENDING)
                }
                val reviewSummary = if (owner.isNotEmpty()) {
                    gitHubApi.getReviewSummary(owner, repo, issue.number)
                } else {
                    com.github.karlsabo.github.ReviewSummary(0, 0, emptyList())
                }
                issue.toPullRequestUiState(checkRunSummary, reviewSummary, headRef)
            }
            emit(Result.success(prStates))
            delay(config.pollIntervalMs)
        }
    }
        .flowOn(Dispatchers.IO)
        .retry(5) { cause ->
            if (cause is IOException) {
                delay(5_000)
                true
            } else {
                false
            }
        }
        .catch { e ->
            logger.error(e) { "Error polling pull requests" }
            emit(Result.failure(e))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Result.success(emptyList()))

    val notifications: StateFlow<Result<List<NotificationUiState>>> = flow {
        while (true) {
            val notifs = gitHubApi.listNotifications()
            val uiStates = notifs
                .filter { it.unread }
                .map { it.toNotificationUiState() }
            emit(Result.success(uiStates))
            delay(config.pollIntervalMs)
        }
    }
        .flowOn(Dispatchers.IO)
        .retry(5) { cause ->
            if (cause is IOException) {
                delay(5_000)
                true
            } else {
                false
            }
        }
        .catch { e ->
            logger.error(e) { "Error polling notifications" }
            emit(Result.failure(e))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Result.success(emptyList()))

    fun openInBrowser(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            desktopLauncher.openUrl(url)
        }
    }

    fun checkoutAndOpen(repoFullName: String, branch: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repoName = repoFullName.substringAfterLast('/')
                val repoPath = "${config.repositoriesBaseDir.trimEnd('/')}/$repoName"
                val worktreePath = gitWorktreeApi.ensureWorktree(repoPath, branch)
                desktopLauncher.openInIdea(worktreePath)
            } catch (e: Exception) {
                logger.error(e) { "Failed to checkout and open $repoFullName branch=$branch" }
            }
        }
    }

    fun approvePullRequest(apiUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.approvePullRequestByUrl(apiUrl)
            } catch (e: Exception) {
                logger.error(e) { "Failed to approve PR $apiUrl" }
            }
        }
    }

    fun submitReview(apiUrl: String, event: ReviewStateValue, reviewComment: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.submitReview(apiUrl, event, reviewComment)
            } catch (e: Exception) {
                logger.error(e) { "Failed to submit review for $apiUrl" }
            }
        }
    }

    fun markNotificationDone(threadId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.markNotificationAsDone(threadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to mark notification done $threadId" }
            }
        }
    }

    fun unsubscribeFromNotification(threadId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.unsubscribeFromNotification(threadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to unsubscribe from notification $threadId" }
            }
        }
    }
}
