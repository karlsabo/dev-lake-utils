package com.github.karlsabo.devlake.ghpanel.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.ghpanel.component.ErrorDialog
import com.github.karlsabo.devlake.ghpanel.component.NotificationPanel
import com.github.karlsabo.devlake.ghpanel.component.PullRequestPanel
import com.github.karlsabo.devlake.ghpanel.viewmodel.GitHubControlPanelViewModel

@Composable
fun GitHubControlPanelScreen(viewModel: GitHubControlPanelViewModel) {
    val pullRequestsResult by viewModel.pullRequests.collectAsState()
    val notificationsResult by viewModel.notifications.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val checkoutInProgress by viewModel.checkoutInProgress.collectAsState()

    actionError?.let { error ->
        ErrorDialog(message = error, onDismiss = { viewModel.clearActionError() })
    }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Pull Requests", style = MaterialTheme.typography.h5)
            PullRequestPanel(
                pullRequestsResult = pullRequestsResult,
                onOpenInBrowser = { viewModel.openInBrowser(it) },
                onCheckoutAndOpen = { repoFullName, branch -> viewModel.checkoutAndOpen(repoFullName, branch) },
                checkoutInProgress = checkoutInProgress,
                modifier = Modifier.weight(1f),
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Notifications", style = MaterialTheme.typography.h5)
            NotificationPanel(
                notificationsResult = notificationsResult,
                onOpenInBrowser = { viewModel.openInBrowser(it) },
                onCheckoutAndOpen = { repoFullName, branch -> viewModel.checkoutAndOpen(repoFullName, branch) },
                checkoutInProgress = checkoutInProgress,
                onApprove = { viewModel.approvePullRequest(it) },
                onSubmitReview = { apiUrl, event, reviewComment ->
                    viewModel.submitReview(
                        apiUrl,
                        event,
                        reviewComment
                    )
                },
                onMarkDone = { viewModel.markNotificationDone(it) },
                onUnsubscribe = { viewModel.unsubscribeFromNotification(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
