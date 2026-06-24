package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.karlsabo.devlake.enghub.component.NotificationPanel
import com.github.karlsabo.devlake.enghub.component.PullRequestPanel
import com.github.karlsabo.devlake.enghub.component.WorktreePanel
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

@Composable
internal fun EngHubPaneContent(
    state: EngHubScreenState,
    actions: EngHubScreenActions,
    modifier: Modifier = Modifier,
) {
    when (state.selectedPane) {
        EngHubPane.PullRequests -> PullRequestsPane(
            state = state.pullRequests,
            actions = actions,
            modifier = modifier,
        )

        EngHubPane.Notifications -> NotificationsPane(
            state = state.notifications,
            actions = actions,
            modifier = modifier,
        )

        EngHubPane.Worktrees -> WorktreesPane(
            state = state.worktrees,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun PullRequestsPane(
    state: PullRequestsPaneState,
    actions: EngHubScreenActions,
    modifier: Modifier = Modifier,
) {
    PullRequestPanel(
        pullRequestsResult = state.result,
        onOpenInBrowser = actions.pullRequests.onOpenInBrowser,
        onCheckoutAndOpen = actions.pullRequests.onCheckoutAndOpen,
        setupStatusFor = state.setupStatuses.setupStatusFor(actions.checkoutWorktreePath),
        modifier = modifier,
    )
}

@Composable
private fun NotificationsPane(
    state: NotificationsPaneState,
    actions: EngHubScreenActions,
    modifier: Modifier = Modifier,
) {
    NotificationPanel(
        notificationsResult = state.result,
        actions = actions.notifications,
        setupStatusFor = state.setupStatuses.setupStatusFor(actions.checkoutWorktreePath),
        actingOnThreadIds = state.actingOnThreadIds,
        modifier = modifier,
    )
}

@Composable
private fun WorktreesPane(
    state: WorktreePanelState,
    actions: EngHubScreenActions,
    modifier: Modifier = Modifier,
) {
    WorktreePanel(
        state = state,
        actions = actions.worktrees,
        modifier = modifier,
    )
}

private fun Map<WorktreePath, WorktreeSetupStatus>.setupStatusFor(
    checkoutWorktreePath: CheckoutWorktreePath,
): (repoFullName: String, branch: String) -> WorktreeSetupStatus? = { repoFullName, branch ->
    this[checkoutWorktreePath(repoFullName, branch)]
}
