package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.PullRequestProviderFailure
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestsResult
import com.github.karlsabo.git.WorktreeSetupStatus

internal fun PullRequestUiState.checkoutSetupStatus(
    setupStatusFor: (repoFullName: String, branch: String) -> WorktreeSetupStatus?,
): WorktreeSetupStatus? = headRef?.let { setupStatusFor(repositoryFullName, it) }

data class PullRequestPanelActions(
    val onOpenInBrowser: (String) -> Unit,
    val onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
    val setupStatusFor: (repoFullName: String, branch: String) -> WorktreeSetupStatus?,
)

@Composable
fun PullRequestPanel(
    pullRequestsResult: PullRequestsResult?,
    actions: PullRequestPanelActions,
    modifier: Modifier = Modifier,
    organizationIdsEmpty: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (organizationIdsEmpty) {
            Text(
                text = "Add at least one organization in Settings to search for pull requests",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.body2,
            )
            return@Box
        }
        if (pullRequestsResult == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        if (pullRequestsResult.pullRequests.isEmpty() && pullRequestsResult.failures.isEmpty()) {
            Text(
                text = "No open pull requests or merge requests",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.body2,
            )
            return@Box
        }
        LazyColumn {
            items(pullRequestsResult.failures, key = { "failure-${it.provider.name}" }) { failure ->
                PullRequestFailureItem(failure)
            }
            items(
                pullRequestsResult.pullRequests,
                key = { "${it.provider.name}-${it.repositoryFullName}-${it.number}" },
            ) { pr ->
                PullRequestItem(
                    pr = pr,
                    onOpenInBrowser = actions.onOpenInBrowser,
                    onCheckoutAndOpen = actions.onCheckoutAndOpen,
                    setupStatus = pr.checkoutSetupStatus(actions.setupStatusFor),
                )
            }
        }
    }
}

@Composable
private fun PullRequestFailureItem(failure: PullRequestProviderFailure) {
    Text(
        text = "Error loading ${failure.provider.label} pull requests: ${failure.message}",
        color = MaterialTheme.colors.error,
        modifier = Modifier.padding(8.dp),
    )
}
