package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogWindow
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun GlobalExistingBranchWorktreeDialog(
    request: PendingGlobalCreateWorktree,
    discovery: GlobalExistingBranchDiscoveryUiState,
    onRequestChange: (PendingGlobalCreateWorktree) -> Unit,
    onConfirm: (ExistingWorktreeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Create Worktree",
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                GlobalExistingBranchWorktreeDialogContent(
                    request = request,
                    discovery = discovery,
                    onRequestChange = onRequestChange,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun GlobalExistingBranchWorktreeDialogContent(
    request: PendingGlobalCreateWorktree,
    discovery: GlobalExistingBranchDiscoveryUiState,
    onRequestChange: (PendingGlobalCreateWorktree) -> Unit,
    onConfirm: (ExistingWorktreeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = globalExistingDialogModel(
        request = request,
        discovery = discovery,
        onRequestChange = onRequestChange,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
    ExistingWorktreeDialogBody(state = model.state, actions = model.actions)
}

@Composable
private fun globalExistingDialogModel(
    request: PendingGlobalCreateWorktree,
    discovery: GlobalExistingBranchDiscoveryUiState,
    onRequestChange: (PendingGlobalCreateWorktree) -> Unit,
    onConfirm: (ExistingWorktreeResult) -> Unit,
    onDismiss: () -> Unit,
): ExistingWorktreeDialogModel {
    val results = globalExistingWorktreeResults(discovery, request.existingBranchQuery)
    val selectedResult = selectedExistingWorktreeResult(request.selectedExistingResult, results)
    var highlightedIndex by remember(request.existingBranchQuery) { mutableIntStateOf(0) }
    val activeHighlightedIndex = highlightedIndex.coerceToWorktreeResults(results)
    return ExistingWorktreeDialogModel(
        state = ExistingWorktreeDialogState(
            title = "Create Worktree",
            mode = null,
            query = request.existingBranchQuery,
            searchLabel = "Search existing branches or PR number",
            results = results,
            isBranchLoading = discovery.isLoading,
            isPullRequestLoading = discovery.repositories.any { it.isPullRequestLoading },
            unsupportedPullRequestMessage = globalUnsupportedPullRequestMessage(
                discovery = discovery,
                query = request.existingBranchQuery,
                results = results,
            ),
            highlightedIndex = activeHighlightedIndex,
            selectedResult = selectedResult,
        ),
        actions = existingWorktreeDialogActions(
            results = results,
            highlightedIndex = activeHighlightedIndex,
            selectedResult = selectedResult,
            callbacks = GlobalExistingDialogCallbacks(request, onRequestChange, onConfirm, onDismiss).toDialogCallbacks(
                onHighlight = { index -> highlightedIndex = index },
            ),
        ),
    )
}

private fun globalUnsupportedPullRequestMessage(
    discovery: GlobalExistingBranchDiscoveryUiState,
    query: String,
    results: List<ExistingWorktreeResult>,
): String? {
    if (results.any { it is ExistingPullRequestWorktreeResult }) return null
    val pullRequestQuery = query.trim()
    return discovery.repositories.firstNotNullOfOrNull { repository ->
        repository.unsupportedPullRequestMessage
            ?.takeIf { repository.pullRequestQuery == pullRequestQuery }
    }
}

private data class GlobalExistingDialogCallbacks(
    val request: PendingGlobalCreateWorktree,
    val onRequestChange: (PendingGlobalCreateWorktree) -> Unit,
    val onConfirm: (ExistingWorktreeResult) -> Unit,
    val onDismiss: () -> Unit,
) {
    fun toDialogCallbacks(
        onHighlight: (Int) -> Unit,
    ): ExistingWorktreeDialogCallbacks = ExistingWorktreeDialogCallbacks(
        onModeChange = {},
        onQueryChange = { query ->
            onRequestChange(request.copy(existingBranchQuery = query, selectedExistingResult = null))
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        selection = ExistingWorktreeSelectionCallbacks(
            onHighlight = onHighlight,
            onSelectResult = { result -> onRequestChange(request.copy(selectedExistingResult = result)) },
            onClearSelection = { onRequestChange(request.copy(selectedExistingResult = null)) },
        ),
    )
}
