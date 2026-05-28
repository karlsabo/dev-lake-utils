package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.ErrorDialog
import com.github.karlsabo.devlake.enghub.component.NotificationPanel
import com.github.karlsabo.devlake.enghub.component.PullRequestPanel
import com.github.karlsabo.devlake.enghub.component.WorktreePanel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel

private enum class EngHubPane(
    val label: String,
    val icon: String,
) {
    PullRequests("Pull Requests", "PR"),
    Notifications("Notifications", "🔔"),
    Worktrees("Worktrees", "🌳"),
}

@Composable
fun EngHubScreen(viewModel: EngHubViewModel) {
    val pullRequestsResult by viewModel.pullRequests.collectAsState()
    val notificationsResult by viewModel.notifications.collectAsState()
    val actionError by viewModel.actionErrorStateFlow.collectAsState()
    val setupStatuses by viewModel.setupStatusesStateFlow.collectAsState()
    val actingOnThreadIds by viewModel.actingOnThreadIdsStateFlow.collectAsState()
    val localRepositories by viewModel.localRepositoriesStateFlow.collectAsState()
    val archivingLocalWorktreePaths by viewModel.archivingLocalWorktreePathsStateFlow.collectAsState()
    val forceArchiveWorktreeRequest by viewModel.forceArchiveWorktreeRequestStateFlow.collectAsState()
    var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }

    actionError?.let { error ->
        ErrorDialog(message = error.message, onDismiss = { viewModel.clearActionError() })
    }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            EngHubSidebar(
                selectedPane = selectedPane,
                onPaneSelected = { selectedPane = it },
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)),
            )
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(selectedPane.label, style = MaterialTheme.typography.h5)
                Spacer(modifier = Modifier.size(8.dp))

                when (selectedPane) {
                    EngHubPane.PullRequests -> PullRequestPanel(
                        pullRequestsResult = pullRequestsResult,
                        onOpenInBrowser = { viewModel.openInBrowser(it) },
                        onCheckoutAndOpen = { repoFullName, branch -> viewModel.checkoutAndOpen(repoFullName, branch) },
                        setupStatusFor = { repoFullName, branch ->
                            setupStatuses[viewModel.checkoutWorktreePath(repoFullName, branch)]
                        },
                        modifier = Modifier.weight(1f),
                    )

                    EngHubPane.Notifications -> NotificationPanel(
                        notificationsResult = notificationsResult,
                        onOpenInBrowser = { viewModel.openInBrowser(it) },
                        onCheckoutAndOpen = { repoFullName, branch -> viewModel.checkoutAndOpen(repoFullName, branch) },
                        setupStatusFor = { repoFullName, branch ->
                            setupStatuses[viewModel.checkoutWorktreePath(repoFullName, branch)]
                        },
                        actingOnThreadIds = actingOnThreadIds,
                        onApprove = { viewModel.approvePullRequest(it) },
                        onSubmitReview = { notification, event, reviewComment ->
                            viewModel.submitReview(notification, event, reviewComment)
                        },
                        onMarkDone = { viewModel.markNotificationDone(it) },
                        onUnsubscribe = { viewModel.unsubscribeFromNotification(it) },
                        modifier = Modifier.weight(1f),
                    )

                    EngHubPane.Worktrees -> WorktreePanel(
                        localRepositories = localRepositories,
                        onAddRepository = { viewModel.pickAndAddLocalRepository() },
                        onToggleRepository = { viewModel.toggleLocalRepositoryExpansion(it) },
                        onOpenWorktree = { repoRootPath, worktreePath ->
                            viewModel.openLocalWorktree(repoRootPath, worktreePath)
                        },
                        onArchiveWorktree = { repoRootPath, worktreePath ->
                            viewModel.archiveLocalWorktree(repoRootPath, worktreePath)
                        },
                        onCreateWorktree = { repoRootPath, baseWorktreePath, baseBranch, targetBranch ->
                            viewModel.createLocalWorktreeFromBase(
                                repoRootPath = repoRootPath,
                                baseWorktreePath = baseWorktreePath,
                                baseBranch = baseBranch,
                                targetBranch = targetBranch,
                            )
                        },
                        forceArchiveRequest = forceArchiveWorktreeRequest,
                        onConfirmForceArchiveWorktree = { repoRootPath, worktreePath ->
                            viewModel.confirmForceArchiveLocalWorktree(repoRootPath, worktreePath)
                        },
                        onDismissForceArchiveWorktree = {
                            viewModel.dismissForceArchiveWorktreeRequest()
                        },
                        setupStatuses = setupStatuses,
                        archivingWorktreePaths = archivingLocalWorktreePaths,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EngHubSidebar(
    selectedPane: EngHubPane,
    onPaneSelected: (EngHubPane) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxHeight().width(56.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EngHubPane.entries.forEach { pane ->
            EngHubSidebarButton(
                pane = pane,
                selected = pane == selectedPane,
                onClick = { onPaneSelected(pane) },
            )
        }
    }
}

@Composable
private fun EngHubSidebarButton(
    pane: EngHubPane,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colors.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(backgroundColor)
            .semantics { contentDescription = pane.label },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = pane.icon,
                color = contentColor,
                style = MaterialTheme.typography.button,
                textAlign = TextAlign.Center,
            )
        }
    }
}
