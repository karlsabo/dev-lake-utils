package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupHandle
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.notifications.NotificationIgnoreStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class EngHubViewModel(
    gitHubServices: EngHubGitHubServices,
    worktreeServices: EngHubWorktreeServices,
    desktopServices: EngHubDesktopServices,
    config: EngHubConfig,
    notificationIgnoreStore: NotificationIgnoreStore,
) : ViewModel() {
    private val state = EngHubViewModelState(
        config = config,
        configWriter = worktreeServices.configWriter,
        worktreeSetupCoordinator = worktreeServices.worktreeSetupCoordinator,
        notificationIgnoreStore = notificationIgnoreStore,
    )
    private val errorReporter = ActionErrorReporter(state)
    private val currentGitHubAccess = MutableStateFlow(CommittedGitHubAccess(gitHubServices, isReady = true))
    private val gitHubServiceActionTracker = GitHubServiceActionTracker(viewModelScope)
    private val localRepositoriesController = LocalRepositoryController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        errorReporter = errorReporter,
    )
    private val checkoutController = CheckoutController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        errorReporter = errorReporter,
    )
    private val localWorktreeCreateController = LocalWorktreeCreateController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        localRepositories = localRepositoriesController,
        errorReporter = errorReporter,
    )
    private val existingWorktreeController = ExistingWorktreeController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        localRepositories = localRepositoriesController,
        pullRequestDiscovery = RepositoryPullRequestWorktreeDiscovery(
            state = state,
            gitWorktreeApi = worktreeServices.gitWorktreeApi,
            launchGitHubAction = { action ->
                gitHubServiceActionTracker.launch(currentGitHubAccess.value.services, action)
            },
        ),
        errorReporter = errorReporter,
    )
    private val globalExistingWorktreeDiscoveryController = GlobalExistingWorktreeDiscoveryController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        launchGitHubAction = { action ->
            gitHubServiceActionTracker.launch(currentGitHubAccess.value.services, action)
        },
    )
    private val archiveController = LocalWorktreeArchiveController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        localRepositories = localRepositoriesController,
        errorReporter = errorReporter,
    )
    private val rebaseController = LocalWorktreeRebaseController(
        viewModel = this,
        state = state,
        worktreeServices = worktreeServices,
        localRepositories = localRepositoriesController,
        errorReporter = errorReporter,
    )
    private val ignoredNotificationPersistence = IgnoredNotificationPersistence(
        state = state,
        notificationIgnoreStore = notificationIgnoreStore,
    )
    private val notificationActionController = NotificationActionController(
        state = state,
        launchGitHubAction = { action ->
            gitHubServiceActionTracker.launch(currentGitHubAccess.value.services, action)
        },
        persistence = ignoredNotificationPersistence,
        errorReporter = errorReporter,
    )

    val actionErrorStateFlow: StateFlow<ActionErrorUiState?> =
        actionErrorState(state.actionErrors)
    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        state.setupStatusesStateFlow
    val localRepositoriesStateFlow: StateFlow<List<LocalRepositoryUiState>> =
        state.localRepositories.asStateFlow()
    internal val configStateFlow: StateFlow<EngHubConfig> = state.config
    internal val lastCreateLocalWorktreeFromBaseRequestStateFlow:
        StateFlow<CreateLocalWorktreeFromBaseRequest?> =
        state.lastCreateLocalWorktreeFromBaseRequest.asStateFlow()
    internal val lastCreateLocalWorktreeFromRepositoryRequestStateFlow:
        StateFlow<CreateLocalWorktreeFromRepositoryRequest?> =
        state.lastCreateLocalWorktreeFromRepositoryRequest.asStateFlow()
    internal val existingBranchDiscoveryStateFlow: StateFlow<ExistingBranchDiscoveryState> =
        state.existingBranchDiscovery.asStateFlow()
    internal val globalExistingBranchDiscoveryStateFlow: StateFlow<GlobalExistingBranchDiscoveryState> =
        state.globalExistingBranchDiscovery.asStateFlow()
    internal val useUnrelatedExistingBranchConfirmationRequestStateFlow:
        StateFlow<UseUnrelatedExistingBranchConfirmationRequest?> =
        state.useUnrelatedExistingBranchConfirmationRequest.asStateFlow()
    internal val rebaseConflictResolutionRequestStateFlow:
        StateFlow<RebaseConflictResolutionRequest?> =
        MappedStateFlow(state.rebaseConflictResolutionRequests) { it.firstOrNull() }
    val archivingLocalWorktreePathsStateFlow: StateFlow<Set<String>> =
        state.archivingLocalWorktreePaths.asStateFlow()
    val rebasingLocalWorktreePathsStateFlow: StateFlow<Set<String>> =
        state.rebasingLocalWorktreePaths.asStateFlow()
    val forceArchiveWorktreeRequestStateFlow: StateFlow<ForceArchiveWorktreeUiState?> =
        state.forceArchiveWorktreeRequest.asStateFlow()
    val actingOnThreadIdsStateFlow: StateFlow<Set<String>> =
        state.actingOnThreadIds.asStateFlow()

    internal suspend fun updateConfig(
        transform: (EngHubConfig) -> EngHubConfig,
    ): EngHubConfig = state.updateConfig(transform)

    internal fun initializeGitHubAccessReadiness(isReady: Boolean) {
        currentGitHubAccess.value = currentGitHubAccess.value.copy(isReady = isReady)
    }

    internal fun updateGitHubAccess(gitHubServices: EngHubGitHubServices, isReady: Boolean) {
        val replacedServices = currentGitHubAccess.value.services
        currentGitHubAccess.value = CommittedGitHubAccess(gitHubServices, isReady)
        if (replacedServices !== gitHubServices) gitHubServiceActionTracker.retire(replacedServices)
    }

    val pullRequests: StateFlow<Result<List<PullRequestUiState>>?> = pullRequestsStateFlow(
        gitHubAccess = currentGitHubAccess,
        configs = state.config,
    )
    val notifications: StateFlow<Result<List<NotificationUiState>>?> = notificationsStateFlow(
        gitHubAccess = currentGitHubAccess,
        configs = state.config,
        state = state,
        persistence = ignoredNotificationPersistence,
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            localRepositoriesController.pollConfiguredLocalRepositoryWorktrees()
        }
    }

    val clearActionError: () -> Unit = errorReporter::clearActionError

    val openInBrowser: (String) -> Unit = { url ->
        viewModelScope.launch(Dispatchers.IO) {
            desktopServices.launcher.openUrl(url)
        }
    }

    val pickAndAddLocalRepository: () -> Unit = localRepositoriesController::pickAndAddLocalRepository
    val addLocalRepository: (String) -> Unit = localRepositoriesController::addLocalRepository
    val toggleLocalRepositoryExpansion: (String) -> Unit =
        localRepositoriesController::toggleLocalRepositoryExpansion
    val checkoutAndOpen: (String, String) -> Job = checkoutController::checkoutAndOpen
    internal val requestCheckoutSetup: (String, String) -> WorktreeSetupHandle =
        checkoutController::requestCheckoutSetup
    val checkoutWorktreePath: (String, String) -> WorktreePath = checkoutController::checkoutWorktreePath
    fun requestCreateLocalWorktreeFromRepository(repoRootPath: String) {
        localWorktreeCreateController.requestCreateLocalWorktreeFromRepository(repoRootPath)
    }

    fun clearCreateLocalWorktreeFromRepositoryRequest() {
        localWorktreeCreateController.clearCreateLocalWorktreeFromRepositoryRequest()
    }

    fun createLocalWorktreeFromBase(
        repoRootPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        baseCommitIsh: String? = null,
    ) = localWorktreeCreateController.createLocalWorktreeFromBase(
        repoRootPath = repoRootPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
        baseCommitIsh = baseCommitIsh,
    )

    internal fun confirmUseUnrelatedExistingBranch(request: UseUnrelatedExistingBranchConfirmationRequest) {
        localWorktreeCreateController.confirmUseUnrelatedExistingBranch(request)
    }

    internal fun dismissUseUnrelatedExistingBranchConfirmation() {
        localWorktreeCreateController.dismissUseUnrelatedExistingBranchConfirmation()
    }

    val discoverExistingBranches: (String) -> Unit = existingWorktreeController::discoverExistingBranches
    val discoverGlobalExistingBranches: () -> Unit = globalExistingWorktreeDiscoveryController::discoverExistingBranches
    val discoverGlobalExistingPullRequests: (String) -> Unit =
        globalExistingWorktreeDiscoveryController::discoverPullRequests
    val discoverExistingPullRequest: (String, String) -> Unit = existingWorktreeController::discoverPullRequest
    val checkoutExistingBranch: (repoRootPath: String, branch: String, existingWorktreePath: String?) -> Unit =
        existingWorktreeController::checkoutExistingBranch
    val openLocalWorktree: (String, String) -> Unit = existingWorktreeController::openLocalWorktree

    val archiveLocalWorktree: (String, String) -> Unit = archiveController::archiveLocalWorktree
    val rebaseLocalWorktreeOntoParent: (String, String, String) -> Unit =
        rebaseController::rebaseLocalWorktreeOntoParent

    internal fun abortRebaseAfterConflict(request: RebaseConflictResolutionRequest) {
        rebaseController.abortRebaseAfterConflict(request)
    }

    internal fun leaveRebaseConflictAsIs(request: RebaseConflictResolutionRequest) {
        rebaseController.leaveRebaseConflictAsIs(request)
    }

    val confirmForceArchiveLocalWorktree: (String, String) -> Unit =
        archiveController::confirmForceArchiveLocalWorktree
    val dismissForceArchiveWorktreeRequest: () -> Unit =
        archiveController::dismissForceArchiveWorktreeRequest
    val approvePullRequest: (NotificationUiState) -> Unit = notificationActionController::approvePullRequest
    val markNotificationDone: (NotificationUiState) -> Unit = notificationActionController::markNotificationDone
    val unsubscribeFromNotification: (NotificationUiState) -> Unit =
        notificationActionController::unsubscribeFromNotification
}
