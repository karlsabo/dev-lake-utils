package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.karlsabo.devlake.enghub.component.ForceArchiveWorktreeActions
import com.github.karlsabo.devlake.enghub.component.GlobalExistingBranchDiscoveryUiState
import com.github.karlsabo.devlake.enghub.component.LocalWorktreeActions
import com.github.karlsabo.devlake.enghub.component.NotificationActions
import com.github.karlsabo.devlake.enghub.component.PendingRebaseConflictResolution
import com.github.karlsabo.devlake.enghub.component.PendingUseUnrelatedExistingBranch
import com.github.karlsabo.devlake.enghub.component.WorktreePanelActions
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.devlake.enghub.component.createRepositoryWorktreeDialogState
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.viewmodel.ActionErrorUiState
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.RebaseConflictResolutionRequest
import com.github.karlsabo.devlake.enghub.viewmodel.UseUnrelatedExistingBranchConfirmationRequest
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal typealias CheckoutWorktreePath = (repoFullName: String, branch: String) -> WorktreePath
private typealias CheckoutAndOpen = (repoFullName: String, branch: String) -> Unit

internal data class EngHubScreenState(
    val selectedPane: EngHubPane,
    val paneAvailability: Map<EngHubPane, EngHubPaneAvailability>,
    val actionError: ActionErrorUiState?,
    val globalExistingBranchDiscovery: GlobalExistingBranchDiscoveryUiState,
    val pullRequests: PullRequestsPaneState,
    val notifications: NotificationsPaneState,
    val worktrees: WorktreePanelState,
    val settings: EngHubSettingsUiState,
)

internal data class PullRequestsPaneState(
    val result: Result<List<PullRequestUiState>>?,
    val organizationIdsEmpty: Boolean,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
)

internal data class NotificationsPaneState(
    val result: Result<List<NotificationUiState>>?,
    val actingOnThreadIds: Set<String>,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
)

internal data class EngHubScreenActions(
    val onPaneSelected: (EngHubPane) -> Unit,
    val onClearActionError: () -> Unit,
    val checkoutWorktreePath: CheckoutWorktreePath,
    val onDiscoverGlobalExistingBranches: () -> Unit,
    val onDiscoverGlobalExistingPullRequests: (query: String) -> Unit,
    val onCheckoutExistingBranch: (repoRootPath: String, branch: String, existingWorktreePath: String?) -> Unit,
    val pullRequests: PullRequestPaneActions,
    val notifications: NotificationActions,
    val worktrees: WorktreePanelActions,
    val settings: EngHubSettingsActions,
)

internal data class EngHubSettingsActions(
    val onGitHubTokenPathChange: (String) -> Unit = {},
    val onChooseGitHubTokenPath: () -> Unit = {},
    val onGitHubTokenChange: (String) -> Unit = {},
    val onOrganizationIdDraftChange: (String) -> Unit = {},
    val onAddOrganizationId: () -> Unit = {},
    val onRemoveOrganizationId: (Int) -> Unit = {},
    val onLocalRepositoryDraftChange: (String) -> Unit = {},
    val onAddLocalRepository: () -> Unit = {},
    val onLocalRepositoryPathChange: (repositoryIndex: Int, path: String) -> Unit = { _, _ -> },
    val onChooseLocalRepositoryPath: (Int) -> Unit = {},
    val onRemoveLocalRepository: (Int) -> Unit = {},
    val onUndoLocalRepositoryRemoval: () -> Unit = {},
    val onSetupCommandDraftChange: (repositoryIndex: Int, command: String) -> Unit = { _, _ -> },
    val onAddSetupCommand: (repositoryIndex: Int, insertionIndex: Int) -> Unit = { _, _ -> },
    val onSetupCommandChange: (repositoryIndex: Int, commandIndex: Int, command: String) -> Unit = { _, _, _ -> },
    val onRemoveSetupCommand: (repositoryIndex: Int, commandIndex: Int) -> Unit = { _, _ -> },
    val onRepositoriesBaseDirChange: (String) -> Unit = {},
    val onChooseRepositoriesBaseDir: () -> Unit = {},
    val onPlanningMarkdownDirChange: (String) -> Unit = {},
    val onChoosePlanningMarkdownDir: () -> Unit = {},
    val onAlertTriageWhereToLookChange: (String) -> Unit = {},
    val onGitHubAuthorChange: (String) -> Unit = {},
    val onPollIntervalChange: (String) -> Unit = {},
    val onWorktreePollIntervalChange: (String) -> Unit = {},
    val onSetupShellChange: (String) -> Unit = {},
)

internal data class PullRequestPaneActions(
    val onOpenInBrowser: (String) -> Unit,
    val onCheckoutAndOpen: CheckoutAndOpen,
)

@Composable
internal fun collectEngHubScreenState(
    viewModel: EngHubViewModel,
    settingsViewModel: EngHubSettingsViewModel,
    selectedPane: EngHubPane,
): EngHubScreenState {
    val settings by settingsViewModel.uiState.collectAsState()
    val paneAvailability = engHubPaneAvailability(settings)
    val activityResults = collectActivityPaneResults(viewModel, paneAvailability)
    val actionError by viewModel.actionErrorStateFlow.collectAsState()
    val setupStatuses by viewModel.setupStatusesStateFlow.collectAsState()
    val actingOnThreadIds by viewModel.actingOnThreadIdsStateFlow.collectAsState()
    val localRepositories by viewModel.localRepositoriesStateFlow.collectAsState()
    val archivingPaths by viewModel.archivingLocalWorktreePathsStateFlow.collectAsState()
    val rebasingPaths by viewModel.rebasingLocalWorktreePathsStateFlow.collectAsState()
    val forceArchiveRequest by viewModel.forceArchiveWorktreeRequestStateFlow.collectAsState()
    val repositoryCreateWorktreeRequest by
        viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.collectAsState()
    val existingBranchDiscovery by viewModel.existingBranchDiscoveryStateFlow.collectAsState()
    val useUnrelatedExistingBranchRequest by
        viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.collectAsState()
    val rebaseConflictResolutionRequest by viewModel.rebaseConflictResolutionRequestStateFlow.collectAsState()
    val globalExistingBranchDiscovery by viewModel.globalExistingBranchDiscoveryStateFlow.collectAsState()

    return EngHubScreenState(
        selectedPane = selectedPane,
        paneAvailability = paneAvailability,
        actionError = actionError,
        globalExistingBranchDiscovery = globalExistingBranchDiscovery.toUiState(),
        pullRequests = PullRequestsPaneState(
            result = activityResults.pullRequests,
            organizationIdsEmpty = settings.committedConfig.organizationIds.isEmpty(),
            setupStatuses = setupStatuses,
        ),
        notifications = NotificationsPaneState(
            result = activityResults.notifications,
            actingOnThreadIds = actingOnThreadIds,
            setupStatuses = setupStatuses,
        ),
        worktrees = WorktreePanelState(
            localRepositories = localRepositories,
            forceArchiveRequest = forceArchiveRequest,
            setupStatuses = setupStatuses,
            archivingWorktreePaths = archivingPaths,
            rebasingWorktreePaths = rebasingPaths,
            repositoryCreateWorktreeRequest = repositoryCreateWorktreeRequest?.let { request ->
                createRepositoryWorktreeDialogState(
                    repoRootPath = request.repoRootPath,
                    baseWorktreePath = request.baseWorktreePath,
                    baseBranch = request.baseBranch,
                )
            },
            existingBranchDiscovery = existingBranchDiscovery.toUiState(),
            useUnrelatedExistingBranchConfirmationRequest = useUnrelatedExistingBranchRequest?.toPendingConfirmation(),
            rebaseConflictResolutionRequest = rebaseConflictResolutionRequest?.toPendingResolution(),
        ),
        settings = settings,
    )
}

private data class ActivityPaneResults(
    val pullRequests: Result<List<PullRequestUiState>>?,
    val notifications: Result<List<NotificationUiState>>?,
)

@Composable
private fun collectActivityPaneResults(
    viewModel: EngHubViewModel,
    availability: Map<EngHubPane, EngHubPaneAvailability>,
): ActivityPaneResults {
    val pullRequests = if (availability.getValue(EngHubPane.PullRequests).isEnabled) {
        val result by viewModel.pullRequests.collectAsState()
        result
    } else {
        null
    }
    val notifications = if (availability.getValue(EngHubPane.Notifications).isEnabled) {
        val result by viewModel.notifications.collectAsState()
        result
    } else {
        null
    }
    return ActivityPaneResults(pullRequests, notifications)
}

internal fun engHubScreenActions(
    viewModel: EngHubViewModel,
    settingsViewModel: EngHubSettingsViewModel,
    onPaneSelected: (EngHubPane) -> Unit,
): EngHubScreenActions = EngHubScreenActions(
    onPaneSelected = onPaneSelected,
    onClearActionError = viewModel.clearActionError,
    checkoutWorktreePath = viewModel.checkoutWorktreePath,
    onDiscoverGlobalExistingBranches = viewModel.discoverGlobalExistingBranches,
    onDiscoverGlobalExistingPullRequests = viewModel.discoverGlobalExistingPullRequests,
    onCheckoutExistingBranch = viewModel.checkoutExistingBranch,
    pullRequests = PullRequestPaneActions(
        onOpenInBrowser = viewModel.openInBrowser,
        onCheckoutAndOpen = { repoFullName, branch ->
            viewModel.checkoutAndOpen(repoFullName, branch)
        },
    ),
    notifications = NotificationActions(
        onOpenInBrowser = viewModel.openInBrowser,
        onCheckoutAndOpen = { repoFullName, branch ->
            viewModel.checkoutAndOpen(repoFullName, branch)
        },
        onApprove = viewModel.approvePullRequest,
        onMarkDone = viewModel.markNotificationDone,
        onUnsubscribe = viewModel.unsubscribeFromNotification,
    ),
    worktrees = engHubWorktreePanelActions(viewModel),
    settings = engHubSettingsActions(settingsViewModel),
)

private fun engHubWorktreePanelActions(viewModel: EngHubViewModel) = WorktreePanelActions(
    onAddRepository = viewModel.pickAndAddLocalRepository,
    onToggleRepository = viewModel.toggleLocalRepositoryExpansion,
    onCreateWorktreeFromRepository = viewModel::requestCreateLocalWorktreeFromRepository,
    onRepositoryCreateWorktreeRequestHandled = viewModel::clearCreateLocalWorktreeFromRepositoryRequest,
    onDiscoverExistingBranches = viewModel.discoverExistingBranches,
    onDiscoverExistingPullRequest = viewModel.discoverExistingPullRequest,
    onCheckoutExistingBranch = viewModel.checkoutExistingBranch,
    onConfirmUseUnrelatedExistingBranch = { request ->
        viewModel.confirmUseUnrelatedExistingBranch(request.toViewModelRequest())
    },
    onDismissUseUnrelatedExistingBranchConfirmation = viewModel::dismissUseUnrelatedExistingBranchConfirmation,
    onAbortRebaseConflict = { request ->
        viewModel.abortRebaseAfterConflict(request.toViewModelRequest())
    },
    onLeaveRebaseConflictAsIs = { request ->
        viewModel.leaveRebaseConflictAsIs(request.toViewModelRequest())
    },
    worktrees = localWorktreeActions(viewModel),
    forceArchive = ForceArchiveWorktreeActions(
        onConfirm = viewModel.confirmForceArchiveLocalWorktree,
        onDismiss = viewModel.dismissForceArchiveWorktreeRequest,
    ),
)

private fun localWorktreeActions(viewModel: EngHubViewModel) = LocalWorktreeActions(
    onOpenWorktree = viewModel.openLocalWorktree,
    onArchiveWorktree = viewModel.archiveLocalWorktree,
    onCreateWorktree = { request ->
        viewModel.createLocalWorktreeFromBase(
            repoRootPath = request.repoRootPath,
            baseWorktreePath = request.baseWorktreePath,
            baseBranch = request.baseBranch,
            targetBranch = request.targetBranch,
            baseCommitIsh = request.baseCommitIsh,
        )
    },
    onRebaseOntoParent = viewModel.rebaseLocalWorktreeOntoParent,
)

private fun engHubSettingsActions(viewModel: EngHubSettingsViewModel) = EngHubSettingsActions(
    onGitHubTokenPathChange = viewModel.gitHubTokenSettings::updateSecretPath,
    onChooseGitHubTokenPath = viewModel.gitHubTokenSettings::chooseSecretPath,
    onGitHubTokenChange = viewModel.gitHubTokenSettings::updateToken,
    onOrganizationIdDraftChange = viewModel::updateOrganizationIdDraft,
    onAddOrganizationId = viewModel::addOrganizationId,
    onRemoveOrganizationId = viewModel::removeOrganizationId,
    onLocalRepositoryDraftChange = viewModel.localRepositorySettings::updateDraft,
    onAddLocalRepository = viewModel.localRepositorySettings::add,
    onLocalRepositoryPathChange = viewModel.localRepositorySettings::updatePath,
    onChooseLocalRepositoryPath = viewModel.localRepositorySettings::choosePath,
    onRemoveLocalRepository = viewModel.localRepositorySettings::remove,
    onUndoLocalRepositoryRemoval = viewModel.localRepositorySettings::undoRemoval,
    onSetupCommandDraftChange = viewModel.setupCommandSettings::updateDraft,
    onAddSetupCommand = viewModel.setupCommandSettings::add,
    onSetupCommandChange = viewModel.setupCommandSettings::update,
    onRemoveSetupCommand = viewModel.setupCommandSettings::remove,
    onRepositoriesBaseDirChange = viewModel.directorySettings::updateRepositoriesBaseDir,
    onChooseRepositoriesBaseDir = viewModel.directorySettings::chooseRepositoriesBaseDir,
    onPlanningMarkdownDirChange = viewModel.directorySettings::updatePlanningMarkdownDir,
    onChoosePlanningMarkdownDir = viewModel.directorySettings::choosePlanningMarkdownDir,
    onAlertTriageWhereToLookChange = viewModel.llmTemplateSettings::updateAlertTriageWhereToLook,
    onGitHubAuthorChange = viewModel.generalTextSettings::updateGitHubAuthor,
    onPollIntervalChange = viewModel.generalTextSettings::updatePollIntervalSeconds,
    onWorktreePollIntervalChange = viewModel.generalTextSettings::updateWorktreePollIntervalSeconds,
    onSetupShellChange = viewModel.generalTextSettings::updateSetupShell,
)

private fun UseUnrelatedExistingBranchConfirmationRequest.toPendingConfirmation(): PendingUseUnrelatedExistingBranch {
    val pendingRequest = PendingUseUnrelatedExistingBranch(
        repoRootPath = repoRootPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
    )
    return pendingRequest
}

private fun PendingUseUnrelatedExistingBranch.toViewModelRequest(): UseUnrelatedExistingBranchConfirmationRequest {
    val viewModelRequest = UseUnrelatedExistingBranchConfirmationRequest(
        repoRootPath = repoRootPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
    )
    return viewModelRequest
}

private fun RebaseConflictResolutionRequest.toPendingResolution(): PendingRebaseConflictResolution {
    val pendingRequest = PendingRebaseConflictResolution(
        repoRootPath = repoRootPath,
        worktreePath = worktreePath,
        parentBranch = parentBranch,
    )
    return pendingRequest
}

private fun PendingRebaseConflictResolution.toViewModelRequest(): RebaseConflictResolutionRequest {
    val viewModelRequest = RebaseConflictResolutionRequest(
        repoRootPath = repoRootPath,
        worktreePath = worktreePath,
        parentBranch = parentBranch,
    )
    return viewModelRequest
}
