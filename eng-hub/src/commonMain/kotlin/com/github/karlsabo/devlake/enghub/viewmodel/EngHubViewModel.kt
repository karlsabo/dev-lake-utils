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
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.notifications.NotificationIgnoreStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        worktreeSetupCoordinator = worktreeServices.worktreeSetupCoordinator,
        notificationIgnoreStore = notificationIgnoreStore,
    )
    private val errorReporter = ActionErrorReporter(state)
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
        errorReporter = errorReporter,
    )
    private val archiveController = LocalWorktreeArchiveController(
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
        viewModel = this,
        state = state,
        gitHubServices = gitHubServices,
        persistence = ignoredNotificationPersistence,
        errorReporter = errorReporter,
    )

    val actionErrorStateFlow: StateFlow<ActionErrorUiState?> =
        actionErrorState(state.actionErrors)
    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        state.setupStatusesStateFlow
    val localRepositoriesStateFlow: StateFlow<List<LocalRepositoryUiState>> =
        state.localRepositories.asStateFlow()
    internal val lastCreateLocalWorktreeFromBaseRequestStateFlow:
        StateFlow<CreateLocalWorktreeFromBaseRequest?> =
        state.lastCreateLocalWorktreeFromBaseRequest.asStateFlow()
    internal val lastCreateLocalWorktreeFromRepositoryRequestStateFlow:
        StateFlow<CreateLocalWorktreeFromRepositoryRequest?> =
        state.lastCreateLocalWorktreeFromRepositoryRequest.asStateFlow()
    internal val useUnrelatedExistingBranchConfirmationRequestStateFlow:
        StateFlow<UseUnrelatedExistingBranchConfirmationRequest?> =
        state.useUnrelatedExistingBranchConfirmationRequest.asStateFlow()
    val archivingLocalWorktreePathsStateFlow: StateFlow<Set<String>> =
        state.archivingLocalWorktreePaths.asStateFlow()
    val forceArchiveWorktreeRequestStateFlow: StateFlow<ForceArchiveWorktreeUiState?> =
        state.forceArchiveWorktreeRequest.asStateFlow()
    val actingOnThreadIdsStateFlow: StateFlow<Set<String>> =
        state.actingOnThreadIds.asStateFlow()

    val pullRequests: StateFlow<Result<List<PullRequestUiState>>?> = pullRequestsStateFlow(
        searchApi = gitHubServices.pullRequestSearchApi,
        reviewApi = gitHubServices.pullRequestReviewApi,
        summaryApi = gitHubServices.pullRequestSummaryApi,
        config = config,
    )
    val notifications: StateFlow<Result<List<NotificationUiState>>?> = notificationsStateFlow(
        gitHubServices = gitHubServices,
        config = config,
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
    ) = localWorktreeCreateController.createLocalWorktreeFromBase(
        repoRootPath = repoRootPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
    )

    internal fun confirmUseUnrelatedExistingBranch(request: UseUnrelatedExistingBranchConfirmationRequest) {
        localWorktreeCreateController.confirmUseUnrelatedExistingBranch(request)
    }

    internal fun dismissUseUnrelatedExistingBranchConfirmation() {
        localWorktreeCreateController.dismissUseUnrelatedExistingBranchConfirmation()
    }

    val openLocalWorktree: (String, String) -> Unit = existingWorktreeController::openLocalWorktree

    internal val requestExistingWorktreeSetup: (String, String) -> WorktreeSetupHandle =
        existingWorktreeController::requestExistingWorktreeSetup
    val archiveLocalWorktree: (String, String) -> Unit = archiveController::archiveLocalWorktree
    val confirmForceArchiveLocalWorktree: (String, String) -> Unit =
        archiveController::confirmForceArchiveLocalWorktree
    val dismissForceArchiveWorktreeRequest: () -> Unit =
        archiveController::dismissForceArchiveWorktreeRequest
    val approvePullRequest: (NotificationUiState) -> Unit = notificationActionController::approvePullRequest
    val submitReview: (NotificationUiState, ReviewStateValue, String?) -> Unit =
        notificationActionController::submitReview
    val markNotificationDone: (NotificationUiState) -> Unit = notificationActionController::markNotificationDone
    val unsubscribeFromNotification: (NotificationUiState) -> Unit =
        notificationActionController::unsubscribeFromNotification
}
