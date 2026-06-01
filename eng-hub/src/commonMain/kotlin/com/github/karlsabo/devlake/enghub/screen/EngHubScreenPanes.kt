package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.devlake.enghub.component.notificationPanel
import com.github.karlsabo.devlake.enghub.component.pullRequestPanel
import com.github.karlsabo.devlake.enghub.component.worktreePanel
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

@Composable
internal fun engHubPaneContent(
    state: EngHubScreenState,
    actions: EngHubScreenActions,
    modifier: Modifier = Modifier,
) {
    when (state.selectedPane) {
        EngHubPane.PullRequests -> pullRequestsPane(
            state = state.pullRequests,
            actions = actions,
            modifier = modifier,
        )

        EngHubPane.Notifications -> notificationsPane(
            state = state.notifications,
            actions = actions,
            modifier = modifier,
        )

        EngHubPane.Worktrees -> worktreesPane(
            state = state.worktrees,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun pullRequestsPane(
    state: PullRequestsPaneState,
    actions: EngHubScreenActions,
    modifier: Modifier,
) {
    pullRequestPanel(
        pullRequestsResult = state.result,
        onOpenInBrowser = actions.pullRequests.onOpenInBrowser,
        onCheckoutAndOpen = actions.pullRequests.onCheckoutAndOpen,
        setupStatusFor = state.setupStatuses.setupStatusFor(actions.checkoutWorktreePath),
        modifier = modifier,
    )
}

@Composable
private fun notificationsPane(
    state: NotificationsPaneState,
    actions: EngHubScreenActions,
    modifier: Modifier,
) {
    notificationPanel(
        notificationsResult = state.result,
        actions = actions.notifications,
        setupStatusFor = state.setupStatuses.setupStatusFor(actions.checkoutWorktreePath),
        actingOnThreadIds = state.actingOnThreadIds,
        modifier = modifier,
    )
}

@Composable
private fun worktreesPane(
    state: WorktreePanelState,
    actions: EngHubScreenActions,
    modifier: Modifier,
) {
    worktreePanel(
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
