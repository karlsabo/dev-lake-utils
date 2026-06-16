package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.karlsabo.devlake.enghub.component.ForceArchiveWorktreeActions
import com.github.karlsabo.devlake.enghub.component.LocalWorktreeActions
import com.github.karlsabo.devlake.enghub.component.NotificationActions
import com.github.karlsabo.devlake.enghub.component.PendingUseUnrelatedExistingBranch
import com.github.karlsabo.devlake.enghub.component.WorktreePanelActions
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.devlake.enghub.component.createRepositoryWorktreeDialogState
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.viewmodel.ActionErrorUiState
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.UseUnrelatedExistingBranchConfirmationRequest
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal typealias CheckoutWorktreePath = (repoFullName: String, branch: String) -> WorktreePath
private typealias CheckoutAndOpen = (repoFullName: String, branch: String) -> Unit

internal data class EngHubScreenState(
    val selectedPane: EngHubPane,
    val actionError: ActionErrorUiState?,
    val pullRequests: PullRequestsPaneState,
    val notifications: NotificationsPaneState,
    val worktrees: WorktreePanelState,
)

internal data class PullRequestsPaneState(
    val result: Result<List<PullRequestUiState>>?,
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
    val pullRequests: PullRequestPaneActions,
    val notifications: NotificationActions,
    val worktrees: WorktreePanelActions,
)

internal data class PullRequestPaneActions(
    val onOpenInBrowser: (String) -> Unit,
    val onCheckoutAndOpen: CheckoutAndOpen,
)

@Composable
internal fun collectEngHubScreenState(
    viewModel: EngHubViewModel,
    selectedPane: EngHubPane,
): EngHubScreenState {
    val pullRequestsResult by viewModel.pullRequests.collectAsState()
    val notificationsResult by viewModel.notifications.collectAsState()
    val actionError by viewModel.actionErrorStateFlow.collectAsState()
    val setupStatuses by viewModel.setupStatusesStateFlow.collectAsState()
    val actingOnThreadIds by viewModel.actingOnThreadIdsStateFlow.collectAsState()
    val localRepositories by viewModel.localRepositoriesStateFlow.collectAsState()
    val archivingPaths by viewModel.archivingLocalWorktreePathsStateFlow.collectAsState()
    val rebasingPaths by viewModel.rebasingLocalWorktreePathsStateFlow.collectAsState()
    val forceArchiveRequest by viewModel.forceArchiveWorktreeRequestStateFlow.collectAsState()
    val repositoryCreateWorktreeRequest by
        viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.collectAsState()
    val useUnrelatedExistingBranchRequest by
        viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.collectAsState()

    return EngHubScreenState(
        selectedPane = selectedPane,
        actionError = actionError,
        pullRequests = PullRequestsPaneState(
            result = pullRequestsResult,
            setupStatuses = setupStatuses,
        ),
        notifications = NotificationsPaneState(
            result = notificationsResult,
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
            useUnrelatedExistingBranchConfirmationRequest = useUnrelatedExistingBranchRequest?.toPendingConfirmation(),
        ),
    )
}

internal fun engHubScreenActions(
    viewModel: EngHubViewModel,
    onPaneSelected: (EngHubPane) -> Unit,
): EngHubScreenActions = EngHubScreenActions(
    onPaneSelected = onPaneSelected,
    onClearActionError = viewModel.clearActionError,
    checkoutWorktreePath = viewModel.checkoutWorktreePath,
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
        onSubmitReview = viewModel.submitReview,
        onMarkDone = viewModel.markNotificationDone,
        onUnsubscribe = viewModel.unsubscribeFromNotification,
    ),
    worktrees = WorktreePanelActions(
        onAddRepository = viewModel.pickAndAddLocalRepository,
        onToggleRepository = viewModel.toggleLocalRepositoryExpansion,
        onCreateWorktreeFromRepository = viewModel::requestCreateLocalWorktreeFromRepository,
        onRepositoryCreateWorktreeRequestHandled = viewModel::clearCreateLocalWorktreeFromRepositoryRequest,
        onConfirmUseUnrelatedExistingBranch = { request ->
            viewModel.confirmUseUnrelatedExistingBranch(request.toViewModelRequest())
        },
        onDismissUseUnrelatedExistingBranchConfirmation = viewModel::dismissUseUnrelatedExistingBranchConfirmation,
        worktrees = LocalWorktreeActions(
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
        ),
        forceArchive = ForceArchiveWorktreeActions(
            onConfirm = viewModel.confirmForceArchiveLocalWorktree,
            onDismiss = viewModel.dismissForceArchiveWorktreeRequest,
        ),
    ),
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
