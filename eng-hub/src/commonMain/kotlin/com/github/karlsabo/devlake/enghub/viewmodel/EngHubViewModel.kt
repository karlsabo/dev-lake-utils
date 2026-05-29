@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.configuredWorktreeSetupCommands
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
import com.github.karlsabo.git.WorktreeBranchNameValidator
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupHandle
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
import com.github.karlsabo.github.PullRequestStatus
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.extractOwnerAndRepo
import com.github.karlsabo.github.pullRequestDetailsUrl
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.system.DesktopLauncher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

private fun loadHiddenThreadIds(
    notificationIgnoreStore: NotificationIgnoreStore,
): Set<String> {
    return runCatching { notificationIgnoreStore.listIgnoredThreadIds() }
        .onFailure { logger.error(it) { "Failed to load persisted ignored notifications" } }
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

data class ActionErrorUiState(
    val id: Long,
    val message: String,
)

internal data class CreateLocalWorktreeFromBaseRequest(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String,
)

private data class ActionErrorQueueState(
    val current: ActionErrorUiState? = null,
    val queuedMessages: List<String> = emptyList(),
    val nextErrorId: Long = 1L,
) {
    fun enqueue(message: String): ActionErrorQueueState =
        if (current == null) withCurrent(message) else copy(queuedMessages = queuedMessages + message)

    fun clearCurrent(): ActionErrorQueueState =
        queuedMessages.firstOrNull()?.let { nextMessage ->
            copy(queuedMessages = queuedMessages.drop(1)).withCurrent(nextMessage)
        } ?: copy(current = null)

    private fun withCurrent(message: String): ActionErrorQueueState =
        copy(
            current = ActionErrorUiState(id = nextErrorId, message = message),
            nextErrorId = nextErrorId + 1,
        )
}

private fun StateFlow<ActionErrorQueueState>.currentActionErrorStateFlow(): StateFlow<ActionErrorUiState?> =
    MappedStateFlow(this) { it.current }

private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val replayCache: List<R>
        get() = listOf(value)

    override val value: R
        get() = transform(source.value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow collection completed unexpectedly")
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
    private val notificationIgnoreStore: NotificationIgnoreStore,
) : ViewModel() {
    private var currentConfig = config

    private val actionErrors = MutableStateFlow(ActionErrorQueueState())
    val actionErrorStateFlow: StateFlow<ActionErrorUiState?> = actionErrors.currentActionErrorStateFlow()

    private val reportedSetupFailureHandlesByPath =
        MutableStateFlow<Map<WorktreePath, List<WorktreeSetupHandle>>>(emptyMap())

    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        worktreeSetupCoordinator.statuses

    private val localRepositories = MutableStateFlow(config.localRepositories.toLocalRepositoryUiStates())
    val localRepositoriesStateFlow: StateFlow<List<LocalRepositoryUiState>> = localRepositories.asStateFlow()
    private val lastCreateLocalWorktreeFromBaseRequest =
        MutableStateFlow<CreateLocalWorktreeFromBaseRequest?>(null)
    internal val lastCreateLocalWorktreeFromBaseRequestStateFlow: StateFlow<CreateLocalWorktreeFromBaseRequest?> =
        lastCreateLocalWorktreeFromBaseRequest.asStateFlow()
    private val localRepositoryExpansionsInFlight = MutableStateFlow<Set<String>>(emptySet())
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
        actionErrors.update { it.clearCurrent() }
    }

    private fun enqueueActionError(message: String) {
        actionErrors.update { it.enqueue(message) }
    }

    private fun enqueueSetupActionErrorOnce(
        worktreePath: WorktreePath,
        setupHandle: WorktreeSetupHandle,
        message: String,
    ): Boolean {
        val shouldReport = markSetupFailureReported(worktreePath, setupHandle)
        if (shouldReport) enqueueActionError(message)
        return shouldReport
    }

    private fun markSetupFailureReported(
        worktreePath: WorktreePath,
        setupHandle: WorktreeSetupHandle,
    ): Boolean {
        while (true) {
            val currentReports = reportedSetupFailureHandlesByPath.value
            val currentHandles = currentReports[worktreePath].orEmpty()
            if (currentHandles.any { it === setupHandle }) return false

            val updatedReports = currentReports + (worktreePath to (currentHandles + setupHandle))
            if (reportedSetupFailureHandlesByPath.compareAndSet(currentReports, updatedReports)) return true
        }
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
            if (cause.isRetriablePollingFailure()) {
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

    private val hiddenThreadIds = MutableStateFlow(loadHiddenThreadIds(notificationIgnoreStore))

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
                                if (processed?.wasMarkedAsDone() == true) {
                                    if (processed.shouldPersistAutomaticallyDoneThread()) {
                                        persistAutomaticallyDoneThreadOrLog(notif)
                                    }
                                } else {
                                    emit(notif)
                                }
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
                if (cause.isRetriablePollingFailure()) {
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
                    enqueueActionError("Repository already configured: $rootPath")
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
                enqueueActionError(e.message ?: "Failed to add local repository")
            }
        }
    }

    fun toggleLocalRepositoryExpansion(repoRootPath: String) {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val repository = localRepositories.value.firstOrNull {
            it.path.normalizedRepoPath() == normalizedRepoRootPath
        } ?: return

        if (repository.isExpanded) {
            clearLocalRepositoryExpansionInFlight(normalizedRepoRootPath)
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

        if (!markLocalRepositoryExpansionInFlight(normalizedRepoRootPath)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val worktrees = listLocalWorktreeUiStates(repoRootPath)
                if (!isLocalRepositoryExpansionInFlight(normalizedRepoRootPath)) return@launch
                updateLocalRepositoryWorktrees(repoRootPath, worktrees, isExpanded = true)
            } catch (e: Exception) {
                logger.error(e) { "Failed to list worktrees for $repoRootPath" }
                enqueueActionError(e.message ?: "Failed to list worktrees")
            } finally {
                clearLocalRepositoryExpansionInFlight(normalizedRepoRootPath)
            }
        }
    }

    fun checkoutAndOpen(repoFullName: String, branch: String): Job {
        val setupRequestResult = runCatching {
            val worktreePath = checkoutWorktreePath(repoFullName, branch)
            worktreePath to requestCheckoutSetup(repoFullName, branch)
        }
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                val (_, setupHandle) = setupRequestResult.getOrThrow()
                setupHandle.await()
                logger.info { "Setup: done" }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to set up worktree"
                val shouldReport = setupRequestResult.getOrNull()?.let { (worktreePath, handle) ->
                    enqueueSetupActionErrorOnce(worktreePath, handle, message)
                } ?: run {
                    enqueueActionError(message)
                    true
                }
                if (shouldReport) logger.error(e) { "Failed to set up $repoFullName branch=$branch" }
            }
        }
    }

    internal fun requestCheckoutSetup(repoFullName: String, branch: String): WorktreeSetupHandle {
        val activeConfig = currentConfig
        val repoPath = checkoutRepoPath(repoFullName, activeConfig)
        val worktreePath = buildWorktreePath(repoPath, branch)
        logger.info { "Setup: requesting checkout setup for $repoFullName branch=$branch at $worktreePath" }
        return worktreeSetupCoordinator.setup(
            WorktreeSetupRequest(
                repoPath = repoPath,
                worktreePath = worktreePath,
                cloneUrl = "https://github.com/$repoFullName.git",
                branch = branch,
                setupShell = activeConfig.setupShell,
                setupCommands = configuredWorktreeSetupCommands(repoPath, activeConfig),
            ),
        )
    }

    fun checkoutWorktreePath(repoFullName: String, branch: String): WorktreePath =
        buildWorktreePath(checkoutRepoPath(repoFullName, currentConfig), branch)

    fun createLocalWorktreeFromBase(
        repoRootPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ) {
        val request = CreateLocalWorktreeFromBaseRequest(
            repoRootPath = repoRootPath,
            baseWorktreePath = baseWorktreePath,
            baseBranch = baseBranch,
            targetBranch = targetBranch,
        )
        logger.info {
            "Create local worktree requested for $repoRootPath base=$baseBranch target=$targetBranch"
        }
        lastCreateLocalWorktreeFromBaseRequest.value = request

        viewModelScope.launch(Dispatchers.IO) {
            var setupRequest: WorktreeSetupRequest? = null
            var setupHandle: WorktreeSetupHandle? = null
            var postCreateRefresh: PostCreateLocalWorktreeRefresh? = null
            try {
                setupRequest = buildCreateLocalWorktreeFromBaseSetupRequest(request)
                setupHandle = worktreeSetupCoordinator.setup(setupRequest)
                postCreateRefresh = launchPostCreateLocalWorktreeRefresh(setupRequest)
                setupHandle.await()
                logger.info { "Setup: created local worktree setup done for ${setupRequest.worktreePath.value}" }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to create worktree"
                val shouldReport = setupRequest?.let { requestWithPath ->
                    setupHandle?.let { handle ->
                        enqueueSetupActionErrorOnce(requestWithPath.worktreePath, handle, message)
                    }
                } ?: run {
                    enqueueActionError(message)
                    true
                }
                if (shouldReport) logger.error(e) {
                    "Failed to create local worktree for $repoRootPath base=$baseBranch target=$targetBranch"
                }
            } finally {
                val refreshStarted = postCreateRefresh?.cancelWaitingOrAwaitStartedRefresh() ?: false
                val submittedSetupRequest = setupRequest
                if (!refreshStarted && submittedSetupRequest != null) {
                    refreshLocalRepositoryWorktreesAfterCreate(submittedSetupRequest)
                }
            }
        }
    }

    private suspend fun PostCreateLocalWorktreeRefresh.cancelWaitingOrAwaitStartedRefresh(): Boolean {
        val refreshStarted = state.cancelWaitingOrReportStarted()
        if (!refreshStarted) job.cancelAndJoin()
        return refreshStarted
    }

    private fun launchPostCreateLocalWorktreeRefresh(setupRequest: WorktreeSetupRequest): PostCreateLocalWorktreeRefresh? {
        if (setupRequest.setupCommands.isEmpty()) return null

        val state = PostCreateLocalWorktreeRefreshState()
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                setupStatusesStateFlow.first { statuses ->
                    statuses[setupRequest.worktreePath] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
                }
                if (!state.markRefreshStarted()) return@launch
                refreshLocalRepositoryWorktreesAfterCreate(setupRequest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) {
                    "Failed to refresh worktrees after creating ${setupRequest.worktreePath.value}"
                }
            }
        }
        return PostCreateLocalWorktreeRefresh(job, state)
    }

    private fun refreshLocalRepositoryWorktreesAfterCreate(setupRequest: WorktreeSetupRequest) {
        refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = setupRequest.repoPath,
            logContext = "after creating worktree ${setupRequest.worktreePath.value}",
        )
    }

    private data class PostCreateLocalWorktreeRefresh(
        val job: Job,
        val state: PostCreateLocalWorktreeRefreshState,
    )

    private class PostCreateLocalWorktreeRefreshState {
        private val guard = Mutex()
        private var status = PostCreateLocalWorktreeRefreshStatus.WAITING

        suspend fun markRefreshStarted(): Boolean = guard.withLock {
            when (status) {
                PostCreateLocalWorktreeRefreshStatus.WAITING -> {
                    status = PostCreateLocalWorktreeRefreshStatus.STARTED
                    true
                }

                PostCreateLocalWorktreeRefreshStatus.STARTED -> true
                PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START -> false
            }
        }

        suspend fun cancelWaitingOrReportStarted(): Boolean = guard.withLock {
            when (status) {
                PostCreateLocalWorktreeRefreshStatus.WAITING -> {
                    status = PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START
                    false
                }

                PostCreateLocalWorktreeRefreshStatus.STARTED -> true
                PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START -> false
            }
        }
    }

    private enum class PostCreateLocalWorktreeRefreshStatus {
        WAITING,
        STARTED,
        CANCELLED_BEFORE_START,
    }

    private fun buildCreateLocalWorktreeFromBaseSetupRequest(
        request: CreateLocalWorktreeFromBaseRequest,
    ): WorktreeSetupRequest {
        val normalizedRepoRootPath = request.repoRootPath.normalizedRepoPath()
        val normalizedBaseWorktreePath = request.baseWorktreePath.normalizedRepoPath()
        validateCreateLocalWorktreeFromBaseRequest(
            repoRootPath = normalizedRepoRootPath,
            baseWorktreePath = normalizedBaseWorktreePath,
            baseBranch = request.baseBranch,
            targetBranch = request.targetBranch,
        )

        val worktreePath = buildWorktreePath(normalizedRepoRootPath, request.targetBranch)
        return WorktreeSetupRequest(
            repoPath = normalizedRepoRootPath,
            worktreePath = worktreePath,
            baseWorktreePath = normalizedBaseWorktreePath,
            baseBranch = request.baseBranch,
            targetBranch = request.targetBranch,
            setupShell = currentConfig.setupShell,
            setupCommands = configuredWorktreeSetupCommands(normalizedRepoRootPath, currentConfig),
        )
    }

    private fun validateCreateLocalWorktreeFromBaseRequest(
        repoRootPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ) {
        require(repoRootPath.isNotEmpty()) { "Repository root path is required" }
        require(baseWorktreePath.isNotEmpty()) { "Base worktree path is required" }
        require(baseBranch != targetBranch) { "Target branch must differ from the base branch" }

        val branchNameValidator = WorktreeBranchNameValidator()
        val baseBranchValidation = branchNameValidator.validate(baseBranch)
        require(baseBranchValidation.isValid) { "Invalid base branch: ${baseBranchValidation.message}" }
        val targetBranchValidation = branchNameValidator.validate(targetBranch)
        require(targetBranchValidation.isValid) { "Invalid target branch: ${targetBranchValidation.message}" }
    }

    fun openLocalWorktree(repoRootPath: String, worktreePath: String) {
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        if (normalizedRepoRootPath.isEmpty() || normalizedWorktreePath.isEmpty()) return

        val worktreeKey = WorktreePath(normalizedWorktreePath)
        viewModelScope.launch(Dispatchers.IO) {
            var setupHandle: WorktreeSetupHandle? = null
            try {
                logger.info { "Setup: requesting existing worktree setup for $normalizedWorktreePath" }
                setupHandle = requestExistingWorktreeSetup(normalizedRepoRootPath, normalizedWorktreePath)
                setupHandle.await()
                logger.info { "Setup: existing worktree setup done for $normalizedWorktreePath" }
            } catch (e: Exception) {
                val message = e.message ?: "Failed to set up worktree"
                val shouldReport = setupHandle?.let { handle ->
                    enqueueSetupActionErrorOnce(worktreeKey, handle, message)
                } ?: run {
                    enqueueActionError(message)
                    true
                }
                if (shouldReport) logger.error(e) { "Failed to set up existing worktree $worktreePath" }
            }
        }
    }

    internal fun requestExistingWorktreeSetup(repoRootPath: String, worktreePath: String): WorktreeSetupHandle {
        val normalizedRepoRootPath = repoRootPath.normalizedRepoPath()
        val normalizedWorktreePath = worktreePath.normalizedRepoPath()
        return worktreeSetupCoordinator.setup(
            WorktreeSetupRequest(
                repoPath = normalizedRepoRootPath,
                worktreePath = WorktreePath(normalizedWorktreePath),
                setupShell = currentConfig.setupShell,
                setupCommands = configuredWorktreeSetupCommands(normalizedRepoRootPath, currentConfig),
            ),
        )
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
            enqueueActionError("Cannot archive root worktree: $worktreePath")
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
                    enqueueActionError(e.message ?: "Failed to archive worktree")
                }
                refreshLocalRepositoryAfterArchiveFailure(repoRootPath)
            } finally {
                archivingLocalWorktreePaths.update { paths -> paths - normalizedWorktreePath }
            }
        }
    }

    fun approvePullRequest(notification: NotificationUiState) {
        val apiUrl = requireNotNull(notification.apiUrl) { "Cannot approve notification without an API URL" }
        runPullRequestDoneAction(
            notification = notification,
            actionLogName = "approve PR $apiUrl",
            actionFailureMessage = "Failed to approve pull request",
        ) {
            gitHubApi.approvePullRequestByUrl(apiUrl)
        }
    }

    fun submitReview(notification: NotificationUiState, event: ReviewStateValue, reviewComment: String?) {
        val apiUrl = requireNotNull(notification.apiUrl) { "Cannot submit review for notification without an API URL" }
        runPullRequestDoneAction(
            notification = notification,
            actionLogName = "submit review for $apiUrl",
            actionFailureMessage = "Failed to submit review",
        ) {
            gitHubApi.submitReview(apiUrl, event, reviewComment)
        }
    }

    private fun runPullRequestDoneAction(
        notification: NotificationUiState,
        actionLogName: String,
        actionFailureMessage: String,
        action: suspend () -> Unit,
    ) {
        val notificationThreadId = notification.notificationThreadId
        actingOnThreadIds.update { it + notificationThreadId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    action()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to $actionLogName" }
                    enqueueActionError(e.message ?: actionFailureMessage)
                    return@launch
                }
                finishPullRequestDoneAction(notification, actionLogName)
            } finally {
                actingOnThreadIds.update { it - notificationThreadId }
            }
        }
    }

    private suspend fun finishPullRequestDoneAction(notification: NotificationUiState, actionLogName: String) {
        val notificationThreadId = notification.notificationThreadId
        hiddenThreadIds.update { it + notificationThreadId }

        try {
            gitHubApi.markNotificationAsDone(notificationThreadId)
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark notification done $notificationThreadId after $actionLogName" }
            hiddenThreadIds.update { it - notificationThreadId }
            enqueueActionError(e.message ?: "Failed to mark notification as done")
            return
        }

        persistDoneThreadOrReport(notification, actionLogName)
    }

    private fun persistDoneThreadOrReport(notification: NotificationUiState, actionLogName: String) {
        val notificationThreadId = notification.notificationThreadId
        try {
            persistDoneThread(notification)
        } catch (e: Exception) {
            logger.error(e) { "Failed to persist done notification $notificationThreadId after $actionLogName" }
            hiddenThreadIds.update { it - notificationThreadId }
            enqueueActionError(e.message ?: "Failed to persist done notification locally")
        }
    }

    fun markNotificationDone(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        actingOnThreadIds.update { it + notificationThreadId }
        hiddenThreadIds.update { it + notificationThreadId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    gitHubApi.markNotificationAsDone(notificationThreadId)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to mark notification done $notificationThreadId" }
                    hiddenThreadIds.update { it - notificationThreadId }
                    enqueueActionError(e.message ?: "Failed to mark notification as done")
                    return@launch
                }
                persistDoneThreadOrReport(notification, "mark notification done $notificationThreadId")
            } finally {
                actingOnThreadIds.update { it - notificationThreadId }
            }
        }
    }

    fun unsubscribeFromNotification(notification: NotificationUiState) {
        val notificationThreadId = notification.notificationThreadId
        actingOnThreadIds.update { it + notificationThreadId }
        hiddenThreadIds.update { it + notificationThreadId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gitHubApi.unsubscribeFromNotification(notificationThreadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to unsubscribe from notification $notificationThreadId" }
                hiddenThreadIds.update { it - notificationThreadId }
                enqueueActionError(e.message ?: "Failed to unsubscribe from notification")
                actingOnThreadIds.update { it - notificationThreadId }
                return@launch
            }

            try {
                persistUnsubscribedThread(notification)
            } catch (e: Exception) {
                logger.error(e) { "Failed to persist unsubscribed notification $notificationThreadId" }
                hiddenThreadIds.update { it - notificationThreadId }
                enqueueActionError(e.message ?: "Failed to persist unsubscribed notification locally")
                actingOnThreadIds.update { it - notificationThreadId }
                return@launch
            }

            try {
                gitHubApi.markNotificationAsDone(notificationThreadId)
            } catch (e: Exception) {
                logger.error(e) { "Failed to mark unsubscribed notification done $notificationThreadId" }
                enqueueActionError(e.message ?: "Failed to mark notification as done")
            } finally {
                actingOnThreadIds.update { it - notificationThreadId }
            }
        }
    }

    private fun persistUnsubscribedThread(notification: NotificationUiState) {
        persistIgnoredThread(notification, NotificationIgnoreReason.UNSUBSCRIBED)
    }

    private fun persistDoneThread(notification: NotificationUiState) {
        persistIgnoredThread(notification, NotificationIgnoreReason.DONE)
    }

    private fun persistIgnoredThread(notification: NotificationUiState, reason: NotificationIgnoreReason) {
        persistIgnoredThread(
            threadId = notification.notificationThreadId,
            repositoryFullName = notification.repositoryFullName,
            subjectType = notification.subjectType,
            reason = reason,
            notificationUpdatedAtEpochMs = notification.updatedAtEpochMs,
        )
    }

    private fun persistAutomaticallyDoneThreadOrLog(notification: Notification) {
        try {
            persistIgnoredThread(notification, NotificationIgnoreReason.DONE)
            hiddenThreadIds.update { it + notification.id }
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to persist automatically done notification ${notification.id}; will retry if GitHub returns it again"
            }
        }
    }

    private fun persistIgnoredThread(
        notification: Notification,
        @Suppress("SameParameterValue") reason: NotificationIgnoreReason,
    ) {
        persistIgnoredThread(
            threadId = notification.id,
            repositoryFullName = notification.repository.fullName,
            subjectType = notification.subject.type,
            reason = reason,
            notificationUpdatedAtEpochMs = notification.updatedAt.toEpochMilliseconds(),
        )
    }

    private fun persistIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        notificationUpdatedAtEpochMs: Long?,
    ) {
        notificationIgnoreStore.saveIgnoredThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
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
        refreshLocalRepositoryWorktreesBestEffort(
            repoRootPath = repoRootPath,
            logContext = "after archive failure",
        )
    }

    private fun refreshLocalRepositoryWorktreesBestEffort(
        repoRootPath: String,
        logContext: String,
    ) {
        try {
            updateLocalRepositoryWorktrees(repoRootPath, listLocalWorktreeUiStates(repoRootPath))
        } catch (e: CancellationException) {
            throw e
        } catch (refreshFailure: Exception) {
            logger.error(refreshFailure) { "Failed to refresh worktrees $logContext for $repoRootPath" }
        }
    }

    private fun markLocalRepositoryExpansionInFlight(normalizedRepoRootPath: String): Boolean {
        while (true) {
            val currentPaths = localRepositoryExpansionsInFlight.value
            if (normalizedRepoRootPath in currentPaths) return false
            if (localRepositoryExpansionsInFlight.compareAndSet(currentPaths, currentPaths + normalizedRepoRootPath)) {
                return true
            }
        }
    }

    private fun clearLocalRepositoryExpansionInFlight(normalizedRepoRootPath: String) {
        localRepositoryExpansionsInFlight.update { it - normalizedRepoRootPath }
    }

    private fun isLocalRepositoryExpansionInFlight(normalizedRepoRootPath: String): Boolean =
        normalizedRepoRootPath in localRepositoryExpansionsInFlight.value

    private fun markLocalWorktreeArchiving(normalizedWorktreePath: String): Boolean {
        while (true) {
            val currentPaths = archivingLocalWorktreePaths.value
            if (normalizedWorktreePath in currentPaths) return false
            if (archivingLocalWorktreePaths.compareAndSet(currentPaths, currentPaths + normalizedWorktreePath)) {
                return true
            }
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

private fun NotificationProcessingResult.Processed.shouldPersistAutomaticallyDoneThread(): Boolean =
    wasMarkedDoneForClosedOrMergedPullRequest() || wasMarkedDoneByAutoApprovalWorkflow()

private fun NotificationProcessingResult.Processed.wasMarkedDoneForClosedOrMergedPullRequest(): Boolean =
    wasMarkedAsDone() && pullRequestStatus.isClosedOrMerged()

private fun NotificationProcessingResult.Processed.wasMarkedDoneByAutoApprovalWorkflow(): Boolean =
    wasMarkedAsDone() && actions.any {
        it is NotificationAction.ApprovedPullRequest || it is NotificationAction.SkippedApproval
    }

private fun NotificationProcessingResult.Processed.wasMarkedAsDone(): Boolean =
    actions.any { it is NotificationAction.MarkedAsDone }

private fun PullRequestStatus?.isClosedOrMerged(): Boolean =
    this == PullRequestStatus.CLOSED || this == PullRequestStatus.MERGED

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
