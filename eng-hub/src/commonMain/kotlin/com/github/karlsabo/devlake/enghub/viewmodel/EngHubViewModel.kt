@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.runConfiguredWorktreeSetup
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.toNotificationUiState
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus.PENDING
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.NotificationAction
import com.github.karlsabo.github.NotificationProcessingResult
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.github.pullRequestDetailsUrl
import com.github.karlsabo.notifications.NotificationSubscriptionStore
import com.github.karlsabo.system.DesktopLauncher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import me.tatarka.inject.annotations.Inject
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

private fun loadHiddenThreadIds(
    notificationSubscriptionStore: NotificationSubscriptionStore,
): Set<String> {
    return runCatching { notificationSubscriptionStore.listUnsubscribedThreadIds() }
        .onFailure { logger.error(it) { "Failed to load persisted unsubscribed notifications" } }
        .getOrElse { emptySet() }
}

private data class NotificationPullRequestDetails(
    val number: Int?,
    val headRef: String?,
)

private suspend fun GitHubApi.getNotificationPullRequestDetails(
    subjectType: String,
    subjectUrl: String?,
): NotificationPullRequestDetails? {
    if (subjectType != "PullRequest" || subjectUrl == null) return null

    return try {
        withContext(Dispatchers.IO) {
            getPullRequestByUrl(subjectUrl).let { pullRequest ->
                NotificationPullRequestDetails(
                    number = pullRequest.number,
                    headRef = pullRequest.head?.ref,
                )
            }
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun GitHubApi.toNotificationUiStateOrNull(notif: Notification): NotificationUiState? {
    val prDetails = getNotificationPullRequestDetails(
        subjectType = notif.subject.type,
        subjectUrl = notif.subject.url,
    )

    if (notif.subject.type == "PullRequest" && prDetails == null) return null

    return notif.toNotificationUiState(
        pullRequestNumber = prDetails?.number,
        headRef = prDetails?.headRef,
    )
}

internal suspend fun GitHubApi.buildPullRequestUiStates(issues: List<Issue>): List<PullRequestUiState> {
    return issues.map { issue ->
        val pr = getPullRequestByUrl(issue.pullRequestDetailsUrl)
        val headRef = pr.head?.ref
        val headSha = pr.head?.sha
        val repoUrl = issue.repositoryUrl ?: ""
        val (owner, repo) = if (repoUrl.isNotEmpty()) extractOwnerAndRepo(repoUrl) else ("" to "")
        val checkRunSummary = if (headSha != null && owner.isNotEmpty()) {
            getCheckRunsForRef(owner, repo, headSha)
        } else {
            CheckRunSummary(0, 0, 0, 0, PENDING)
        }
        val reviewSummary = if (owner.isNotEmpty()) {
            getReviewSummary(owner, repo, issue.number)
        } else {
            ReviewSummary(0, 0, emptyList())
        }
        issue.toPullRequestUiState(checkRunSummary, reviewSummary, headRef)
    }
}

@Inject
class EngHubViewModel(
    private val gitHubApi: GitHubApi,
    private val gitHubNotificationService: GitHubNotificationService,
    private val gitWorktreeApi: GitWorktreeApi,
    private val desktopLauncher: DesktopLauncher,
    private val config: EngHubConfig,
    private val notificationSubscriptionStore: NotificationSubscriptionStore,
) : ViewModel() {

    private val actionError = MutableStateFlow<String?>(null)
    val actionErrorStateFlow: StateFlow<String?> = actionError.asStateFlow()

    private val checkoutInProgress = MutableStateFlow(false)
    val checkoutInProgressStateFlow: StateFlow<Boolean> = checkoutInProgress.asStateFlow()

    private val actingOnThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val actingOnThreadIdsStateFlow: StateFlow<Set<String>> = actingOnThreadIds.asStateFlow()

    fun clearActionError() {
        actionError.value = null
    }

    val pullRequests: StateFlow<Result<List<PullRequestUiState>>?> = flow {
        while (true) {
            val issues = gitHubApi.getOpenPullRequestsByAuthor(config.organizationIds, config.gitHubAuthor)
            val prStates = gitHubApi.buildPullRequestUiStates(issues)
            emit(Result.success(prStates))
            delay(config.pollIntervalMs.milliseconds)
        }
    }
        .flowOn(Dispatchers.IO)
        .retry(5) { cause ->
            if (cause is IOException) {
                delay(5_000.milliseconds)
                true
            } else {
                false
            }
        }
        .catch { e ->
            logger.error(e) { "Error polling pull requests" }
            emit(Result.failure(e))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val hiddenThreadIds = MutableStateFlow(loadHiddenThreadIds(notificationSubscriptionStore))

    private val polledNotifications: Flow<Result<List<NotificationUiState>>> =
        flow {
            while (true) {
                val notifs = gitHubApi.listNotifications()
                val uiStates: List<NotificationUiState> =
                    notifs
                        .filterNot { it.id in hiddenThreadIds.value }
                        .asSequence()
                        .asFlow()
                        .flatMapMerge(concurrency = 16) { notif ->
                            flow {
                                val processed = withContext(Dispatchers.IO) {
                                    gitHubNotificationService.processNotification(notif)
                                } as? NotificationProcessingResult.Processed
                                val markedDone =
                                    processed?.actions?.any { it is NotificationAction.MarkedAsDone } ?: false
                                if (!markedDone) emit(notif)
                            }
                        }
                        .mapNotNull { notif -> gitHubApi.toNotificationUiStateOrNull(notif) }
                        .toList()
                emit(Result.success(uiStates))
                delay(config.pollIntervalMs.milliseconds)
            }
        }
            .flowOn(Dispatchers.IO)
            .retry(5) { cause ->
                if (cause is IOException) {
                    delay(5_000.milliseconds)
                    true
                } else {
                    false
                }
            }
            .catch { e ->
                logger.error(e) { "Error polling notifications" }
                emit(Result.failure(e))
            }

    val notifications: StateFlow<Result<List<NotificationUiState>>?> = combine(
        polledNotifications,
        hiddenThreadIds,
    ) { result, hidden ->
        result.map { list -> list.filter { it.notificationThreadId !in hidden } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openInBrowser(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            desktopLauncher.openUrl(url)
        }
    }

    fun checkoutAndOpen(repoFullName: String, branch: String) {
        viewModelScope.launch(Dispatchers.IO) {
            checkoutInProgress.value = true
            try {
                val repoName = repoFullName.substringAfterLast('/')
                val repoPath = "${config.repositoriesBaseDir.trimEnd('/')}/$repoName"
                val cloneUrl = "https://github.com/$repoFullName.git"
                logger.info { "Setup: ensuring repository at $repoPath" }
                gitWorktreeApi.ensureRepository(repoPath, cloneUrl)
                logger.info { "Setup: ensuring worktree for branch $branch" }
                val worktreePath = gitWorktreeApi.ensureWorktree(repoPath, branch)
                val setupResult = runConfiguredWorktreeSetup(repoPath, worktreePath, config)
                when {
                    setupResult == null -> logger.info { "Setup: no configured commands for $repoPath" }
                    setupResult.exitCode != 0 -> {
                        val detail = setupResult.stderr.ifEmpty { setupResult.stdout }.ifBlank { "No command output" }
                        val warningMessage =
                            "Setup commands failed for $worktreePath (exit code ${setupResult.exitCode}): $detail"
                        logger.warn { warningMessage }
                        actionError.value = warningMessage
                    }

                    else -> logger.info { "Setup: commands completed for $worktreePath" }
                }
                logger.info { "Setup: done" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to set up $repoFullName branch=$branch" }
                actionError.value = e.message ?: "Failed to set up worktree"
            } finally {
                checkoutInProgress.value = false
            }
        }
    }

    fun approvePullRequest(notificationThreadId: String, apiUrl: String) {
        actingOnThreadIds.value += notificationThreadId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.approvePullRequestByUrl(apiUrl)
                gitHubApi.markNotificationAsDone(notificationThreadId)
                hiddenThreadIds.value += notificationThreadId
            } catch (e: Exception) {
                logger.error(e) { "Failed to approve PR $apiUrl" }
                actionError.value = e.message ?: "Failed to approve pull request"
            } finally {
                actingOnThreadIds.value -= notificationThreadId
            }
        }
    }

    fun submitReview(notificationThreadId: String, apiUrl: String, event: ReviewStateValue, reviewComment: String?) {
        actingOnThreadIds.value += notificationThreadId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.submitReview(apiUrl, event, reviewComment)
                gitHubApi.markNotificationAsDone(notificationThreadId)
                hiddenThreadIds.value += notificationThreadId
            } catch (e: Exception) {
                logger.error(e) { "Failed to submit review for $apiUrl" }
                actionError.value = e.message ?: "Failed to submit review"
            } finally {
                actingOnThreadIds.value -= notificationThreadId
            }
        }
    }

    fun markNotificationDone(notificationThreadId: String) {
        actingOnThreadIds.value += notificationThreadId
        hiddenThreadIds.value += notificationThreadId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.markNotificationAsDone(notificationThreadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to mark notification done $notificationThreadId" }
                hiddenThreadIds.value -= notificationThreadId
                actionError.value = e.message ?: "Failed to mark notification as done"
            } finally {
                actingOnThreadIds.value -= notificationThreadId
            }
        }
    }

    fun unsubscribeFromNotification(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        actingOnThreadIds.value += notificationThreadId
        hiddenThreadIds.value += notificationThreadId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.unsubscribeFromNotification(notificationThreadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to unsubscribe from notification $notificationThreadId" }
                hiddenThreadIds.value -= notificationThreadId
                actionError.value = e.message ?: "Failed to unsubscribe from notification"
                actingOnThreadIds.value -= notificationThreadId
                return@launch
            }

            try {
                persistUnsubscribedThread(notification)
            } catch (e: Exception) {
                logger.error(e) { "Failed to persist unsubscribed notification $notificationThreadId" }
                hiddenThreadIds.value -= notificationThreadId
                actionError.value = e.message ?: "Failed to persist unsubscribed notification locally"
                actingOnThreadIds.value -= notificationThreadId
                return@launch
            }

            try {
                gitHubApi.markNotificationAsDone(notificationThreadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to mark unsubscribed notification done $notificationThreadId" }
                actionError.value = e.message ?: "Failed to mark notification as done"
            } finally {
                actingOnThreadIds.value -= notificationThreadId
            }
        }
    }

    private fun persistUnsubscribedThread(notification: NotificationUiState) {
        notificationSubscriptionStore.saveUnsubscribedThread(
            threadId = notification.notificationThreadId,
            repositoryFullName = notification.repositoryFullName,
            subjectType = notification.subjectType,
            unsubscribedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }
}
