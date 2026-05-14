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
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.git.WorktreeSetupStatus

@Composable
fun PullRequestPanel(
    pullRequestsResult: Result<List<PullRequestUiState>>?,
    onOpenInBrowser: (String) -> Unit,
    onCheckoutAndOpen: (repoFullName: String, branch: String) -> Unit,
    setupStatusFor: (repoFullName: String, branch: String) -> WorktreeSetupStatus? = { _, _ -> null },
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (pullRequestsResult == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        pullRequestsResult.fold(
            onSuccess = { pullRequests ->
                if (pullRequests.isEmpty()) {
                    Text(
                        text = "No open pull requests",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.body2,
                    )
                } else {
                    LazyColumn {
                        items(pullRequests, key = { it.number }) { pr ->
                            PullRequestItem(
                                pr = pr,
                                onOpenInBrowser = onOpenInBrowser,
                                onCheckoutAndOpen = onCheckoutAndOpen,
                                setupStatus = pr.headRef?.let { setupStatusFor(pr.repositoryFullName, it) },
                            )
                        }
                    }
                }
            },
            onFailure = { error ->
                Text(
                    text = "Error loading pull requests: ${error.message}",
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(8.dp),
                )
            },
        )
    }
}
