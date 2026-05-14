@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.configuredWorktreeSetupCommands
import com.github.karlsabo.devlake.enghub.runConfiguredWorktreeSetup
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.toLocalRepositoryUiStates
import com.github.karlsabo.devlake.enghub.state.toLocalWorktreeUiStates
import com.github.karlsabo.devlake.enghub.state.toNotificationUiState
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.git.GitCommandException
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import me.tatarka.inject.annotations.Inject
import java.io.IOException
import java.util.Collections
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
    private val worktreeSetupCoordinator: WorktreeSetupCoordinator,
    private val desktopLauncher: DesktopLauncher,
    private val directoryPicker: DirectoryPicker,
    private val configWriter: EngHubConfigWriter,
    private val config: EngHubConfig,
    private val notificationSubscriptionStore: NotificationSubscriptionStore,
) : ViewModel() {
    private var currentConfig = config

    private val actionError = MutableStateFlow<String?>(null)
    val actionErrorStateFlow: StateFlow<String?> = actionError.asStateFlow()

    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        worktreeSetupCoordinator.statuses

    private val localRepositories = MutableStateFlow(config.localRepositories.toLocalRepositoryUiStates())
    val localRepositoriesStateFlow: StateFlow<List<LocalRepositoryUiState>> = localRepositories.asStateFlow()
    private val localRepositoryExpansionsInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val openingLocalWorktreePaths = MutableStateFlow<Set<String>>(emptySet())
    val openingLocalWorktreePathsStateFlow: StateFlow<Set<String>> = openingLocalWorktreePaths.asStateFlow()
    private val archivingLocalWorktreePaths = MutableStateFlow<Set<String>>(emptySet())
    val archivingLocalWorktreePathsStateFlow: StateFlow<Set<String>> = archivingLocalWorktreePaths.asStateFlow()
    private val forceArchiveWorktreeRequest = MutableStateFlow<ForceArchiveWorktreeUiState?>(null)
    val forceArchiveWorktreeRequestStateFlow: StateFlow<ForceArchiveWorktreeUiState?> =
        forceArchiveWorktreeRequest.asStateFlow()

    private val actingOnThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val actingOnThreadIdsStateFlow: StateFlow<Set<String>> = actingOnThreadIds.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pollConfiguredLocalRepositoryWorktrees()
        }
    }

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

    fun pickAndAddLocalRepository() {
        viewModelScope.launch {
            val selectedPath = directoryPicker.pickDirectory("Add Local Repository")
            if (selectedPath != null) {
                addLocalRepository(selectedPath)
            }
        }
    }

    fun addLocalRepository(selectedPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repositoryWorktrees = gitWorktreeApi.resolveRepositoryRoot(selectedPath)
                val rootPath = repositoryWorktrees.rootPath
                if (currentConfig.localRepositories.any { it.path.normalizedRepoPath() == rootPath.normalizedRepoPath() }) {
                    actionError.value = "Repository already configured: $rootPath"
                    return@launch
                }

                val newConfig = currentConfig.copy(
                    localRepositories = currentConfig.localRepositories + LocalRepositoryConfig(path = rootPath),
                )
                configWriter.save(newConfig)
                currentConfig = newConfig
                localRepositories.update { repositories ->
                    newConfig.localRepositories
                        .toLocalRepositoryUiStates()
                        .withPreservedWorktrees(
                            previousRepositories = repositories,
                            updatedRootPath = rootPath,
                            updatedWorktrees = repositoryWorktrees.worktrees.toLocalWorktreeUiStates(rootPath),
                            expandUpdatedRepository = true,
                        )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to add local repository from $selectedPath" }
                actionError.value = e.message ?: "Failed to add local repository"
            }
        }
    }

    fun toggleLocalRepositoryExpansion(repoRootPath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val repository = localRepositories.value.firstOrNull {
            it.path.normalizedRepoPath() == normalizedRepoRootPath
        } ?: return

        if (repository.isExpanded) {
            localRepositoryExpansionsInFlight -= normalizedRepoRootPath
            localRepositories.update { repositories ->
                repositories.map { currentRepository ->
                    if (currentRepository.path.normalizedRepoPath() == normalizedRepoRootPath) {
                        currentRepository.copy(isExpanded = false)
                    } else {
                        currentRepository
                    }
                }
            }
            return
        }

        if (!localRepositoryExpansionsInFlight.add(normalizedRepoRootPath)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val worktrees = listLocalWorktreeUiStates(repoRootPath)
                if (normalizedRepoRootPath !in localRepositoryExpansionsInFlight) return@launch
                updateLocalRepositoryWorktrees(repoRootPath, worktrees, isExpanded = true)
            } catch (e: Exception) {
                logger.error(e) { "Failed to list worktrees for $repoRootPath" }
                actionError.value = e.message ?: "Failed to list worktrees"
            } finally {
                localRepositoryExpansionsInFlight -= normalizedRepoRootPath
            }
        }
    }

    fun checkoutAndOpen(repoFullName: String, branch: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeConfig = currentConfig
                val repoPath = checkoutRepoPath(repoFullName, activeConfig)
                val worktreePath = buildWorktreePath(repoPath, branch)
                logger.info { "Setup: requesting checkout setup for $repoFullName branch=$branch at $worktreePath" }
                val setup = worktreeSetupCoordinator.setup(
                    WorktreeSetupRequest(
                        repoPath = repoPath,
                        worktreePath = worktreePath,
                        cloneUrl = "https://github.com/$repoFullName.git",
                        branch = branch,
                        setupShell = activeConfig.setupShell,
                        setupCommands = configuredWorktreeSetupCommands(repoPath, activeConfig),
                    ),
                )
                setup.await()
                logger.info { "Setup: done" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to set up $repoFullName branch=$branch" }
                actionError.value = e.message ?: "Failed to set up worktree"
            }
        }
    }

    fun checkoutWorktreePath(repoFullName: String, branch: String): WorktreePath =
        buildWorktreePath(checkoutRepoPath(repoFullName, currentConfig), branch)

    fun openLocalWorktree(repoRootPath: String, worktreePath: String) {
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        if (normalizedWorktreePath.isEmpty() || !markLocalWorktreeOpening(normalizedWorktreePath)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                logger.info { "Setup: opening existing worktree $worktreePath for $repoRootPath" }
                runConfiguredSetupForWorktree(repoRootPath, worktreePath, currentConfig)
            } catch (e: Exception) {
                logger.error(e) { "Failed to set up existing worktree $worktreePath" }
                actionError.value = e.message ?: "Failed to set up worktree"
            } finally {
                openingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
            }
        }
    }

    fun archiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        archiveLocalWorktree(repoRootPath, worktreePath, force = false)
    }

    fun confirmForceArchiveLocalWorktree(repoRootPath: String, worktreePath: String) {
        val request = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
        if (!forceArchiveWorktreeRequest.compareAndSet(expect = request, update = null)) return
        archiveLocalWorktree(repoRootPath, worktreePath, force = true)
    }

    fun dismissForceArchiveWorktreeRequest() {
        forceArchiveWorktreeRequest.value = null
    }

    private fun archiveLocalWorktree(repoRootPath: String, worktreePath: String, force: Boolean) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        if (normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty()) return
        if (normalizedRepoRootPath == normalizedWorktreePath) {
            actionError.value = "Cannot archive root worktree: $worktreePath"
            return
        }
        if (!markLocalWorktreeArchiving(normalizedWorktreePath)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                logger.info { "Archiving worktree $worktreePath for $repoRootPath force=$force" }
                gitWorktreeApi.archiveWorktree(repoRootPath, worktreePath, force = force)
                updateLocalRepositoryWorktrees(repoRootPath, listLocalWorktreeUiStates(repoRootPath))
            } catch (e: Exception) {
                logger.error(e) { "Failed to archive worktree $worktreePath" }
                if (!force && e.isDirtyWorktreeArchiveFailure()) {
                    archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
                    forceArchiveWorktreeRequest.value = ForceArchiveWorktreeUiState(repoRootPath, worktreePath)
                } else {
                    actionError.value = e.message ?: "Failed to archive worktree"
                }
                refreshLocalRepositoryAfterArchiveFailure(repoRootPath)
            } finally {
                archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
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

    private suspend fun pollConfiguredLocalRepositoryWorktrees() {
        while (true) {
            delay(currentConfig.worktreePollIntervalMs.coerceAtLeast(1).milliseconds)
            refreshConfiguredLocalRepositoryWorktrees()
        }
    }

    private fun refreshConfiguredLocalRepositoryWorktrees() {
        currentConfig.localRepositories
            .asSequence()
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.normalizedRepoPath() }
            .forEach { repoRootPath ->
                try {
                    updateLocalRepositoryWorktrees(repoRootPath, listLocalWorktreeUiStates(repoRootPath))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to poll worktrees for $repoRootPath" }
                }
            }
    }

    private fun listLocalWorktreeUiStates(repoRootPath: String): List<LocalWorktreeUiState> {
        return gitWorktreeApi.listWorktrees(repoRootPath).toLocalWorktreeUiStates(repoRootPath)
    }

    private fun refreshLocalRepositoryAfterArchiveFailure(repoRootPath: String) {
        try {
            updateLocalRepositoryWorktrees(repoRootPath, listLocalWorktreeUiStates(repoRootPath))
        } catch (e: CancellationException) {
            throw e
        } catch (refreshFailure: Exception) {
            logger.error(refreshFailure) { "Failed to refresh worktrees after archive failure for $repoRootPath" }
        }
    }

    private fun markLocalWorktreeOpening(normalizedWorktreePath: String): Boolean {
        while (true) {
            val currentPaths = openingLocalWorktreePaths.value
            if (normalizedWorktreePath in currentPaths) return false
            if (openingLocalWorktreePaths.compareAndSet(currentPaths, currentPaths + normalizedWorktreePath)) {
                return true
            }
        }
    }

    private fun markLocalWorktreeArchiving(normalizedWorktreePath: String): Boolean {
        while (true) {
            val currentPaths = archivingLocalWorktreePaths.value
            if (normalizedWorktreePath in currentPaths) return false
            if (archivingLocalWorktreePaths.compareAndSet(currentPaths, currentPaths + normalizedWorktreePath)) {
                return true
            }
        }
    }

    private fun runConfiguredSetupForWorktree(
        repoPath: String,
        worktreePath: String,
        setupConfig: EngHubConfig,
    ) {
        val setupResult = runConfiguredWorktreeSetup(repoPath, worktreePath, setupConfig)
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
    }

    private fun updateLocalRepositoryWorktrees(
        repoRootPath: String,
        worktrees: List<LocalWorktreeUiState>,
        isExpanded: Boolean? = null,
    ) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        localRepositories.update { repositories ->
            repositories.map { currentRepository ->
                if (currentRepository.path.normalizedRepoPath() == normalizedRepoRootPath) {
                    currentRepository.copy(
                        isExpanded = isExpanded ?: currentRepository.isExpanded,
                        worktrees = worktrees,
                    )
                } else {
                    currentRepository
                }
            }
        }
    }
}

private fun String.normalizedRepoPath(): String = trim().trimEnd('/', '\\')

private fun checkoutRepoPath(repoFullName: String, config: EngHubConfig): String {
    val repoName = repoFullName.substringAfterLast('/')
    return "${config.repositoriesBaseDir.trimEnd('/')}/$repoName"
}

private val dirtyWorktreeGitOutputMarkers = listOf(
    "contains modified",
    "modified or untracked",
    "contains untracked",
    "dirty",
)

private val dirtyWorktreeMessageMarkers = dirtyWorktreeGitOutputMarkers - "dirty"

private fun Throwable.isDirtyWorktreeArchiveFailure(): Boolean {
    val commandOutput = causeSequence()
        .filterIsInstance<GitCommandException>()
        .joinToString(separator = "\n") { it.gitOutput }
        .lowercase()
    if (commandOutput.isNotBlank()) return commandOutput.containsAny(dirtyWorktreeGitOutputMarkers)

    return message.orEmpty()
        .lowercase()
        .containsAny(dirtyWorktreeMessageMarkers)
}

private fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeSequence
    while (current != null) {
        yield(current)
        current = current.cause
    }
}

private fun String.containsAny(markers: List<String>): Boolean = markers.any(::contains)

private fun List<LocalRepositoryUiState>.withPreservedWorktrees(
    previousRepositories: List<LocalRepositoryUiState>,
    updatedRootPath: String,
    updatedWorktrees: List<LocalWorktreeUiState>,
    expandUpdatedRepository: Boolean = false,
): List<LocalRepositoryUiState> {
    val normalizedUpdatedRootPath = updatedRootPath.normalizedRepoPath()
    val previousRepositoriesByPath = previousRepositories.associateBy { repository ->
        repository.path.normalizedRepoPath()
    }

    return map { repository ->
        val normalizedPath = repository.path.normalizedRepoPath()
        val previousRepository = previousRepositoriesByPath[normalizedPath]
        val worktrees = if (normalizedPath == normalizedUpdatedRootPath) {
            updatedWorktrees
        } else {
            previousRepository?.worktrees.orEmpty()
        }
        val isExpanded = if (normalizedPath == normalizedUpdatedRootPath && expandUpdatedRepository) {
            true
        } else {
            previousRepository?.isExpanded ?: repository.isExpanded
        }
        repository.copy(isExpanded = isExpanded, worktrees = worktrees)
    }
}
