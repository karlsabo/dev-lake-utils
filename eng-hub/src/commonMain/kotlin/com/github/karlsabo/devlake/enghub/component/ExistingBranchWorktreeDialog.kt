package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun CreateWorktreeModeSelector(
    mode: CreateWorktreeMode,
    onModeChange: (CreateWorktreeMode) -> Unit,
) {
    Row {
        Button(
            onClick = { onModeChange(CreateWorktreeMode.NEW) },
            modifier = Modifier.testTag("new-worktree-mode"),
            enabled = mode != CreateWorktreeMode.NEW,
        ) {
            Text("New")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { onModeChange(CreateWorktreeMode.EXISTING) },
            enabled = mode != CreateWorktreeMode.EXISTING,
        ) {
            Text("Existing")
        }
    }
}

private data class ExistingWorktreeDialogState(
    val request: PendingCreateWorktree,
    val results: List<ExistingWorktreeResult>,
    val isBranchLoading: Boolean,
    val isPullRequestLoading: Boolean,
    val unsupportedPullRequestMessage: String?,
    val highlightedIndex: Int?,
    val selectedResult: ExistingWorktreeResult?,
)

private data class ExistingWorktreeDialogActions(
    val onRequestChange: (PendingCreateWorktree) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onKeyEvent: (KeyEvent) -> Boolean,
)

private data class ExistingWorktreeKeyboardCallbacks(
    val onHighlight: (Int) -> Unit,
    val onSelect: (ExistingWorktreeResult) -> Unit,
    val onConfirm: () -> Unit,
)

private data class ExistingWorktreeRowActions(
    val onSelect: (ExistingWorktreeResult) -> Unit,
    val onKeyEvent: (KeyEvent) -> Boolean,
)

@Composable
internal fun ExistingBranchWorktreeDialogContent(
    request: PendingCreateWorktree,
    discovery: ExistingBranchDiscoveryUiState,
    onRequestChange: (PendingCreateWorktree) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val activeDiscovery = discovery.takeIf { it.repoRootPath == request.repoRootPath }
        ?: ExistingBranchDiscoveryUiState(repoRootPath = request.repoRootPath)
    val results = existingWorktreeResults(activeDiscovery, request.existingBranchQuery)
    val selectedResult = selectedExistingWorktreeResult(request.selectedExistingResult, results)
    var highlightedIndex by remember(request.repoRootPath, request.existingBranchQuery) { mutableIntStateOf(0) }
    val activeHighlightedIndex = highlightedIndex.coerceToWorktreeResults(results)
    val keyboardCallbacks = ExistingWorktreeKeyboardCallbacks(
        onHighlight = { index ->
            highlightedIndex = index
            if (selectedResult != results[index]) {
                onRequestChange(request.copy(selectedExistingResult = null))
            }
        },
        onSelect = { result -> onRequestChange(request.copy(selectedExistingResult = result)) },
        onConfirm = onConfirm,
    )

    ExistingWorktreeDialogBody(
        state = ExistingWorktreeDialogState(
            request = request,
            results = results,
            isBranchLoading = activeDiscovery.isLoading,
            isPullRequestLoading = activeDiscovery.isPullRequestLoading,
            unsupportedPullRequestMessage = activeDiscovery.unsupportedPullRequestMessage
                ?.takeIf { activeDiscovery.pullRequestQuery == request.existingBranchQuery.trim() },
            highlightedIndex = activeHighlightedIndex,
            selectedResult = selectedResult,
        ),
        actions = ExistingWorktreeDialogActions(
            onRequestChange = onRequestChange,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            onKeyEvent = { event ->
                handleExistingWorktreeKeyEvent(
                    event = event,
                    results = results,
                    highlightedIndex = activeHighlightedIndex,
                    selectedResult = selectedResult,
                    callbacks = keyboardCallbacks,
                )
            },
        ),
    )
}

@Composable
private fun ExistingWorktreeDialogBody(
    state: ExistingWorktreeDialogState,
    actions: ExistingWorktreeDialogActions,
) {
    val request = state.request
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.isEnterKey()) false else actions.onKeyEvent(event)
            },
    ) {
        Text(text = "Create Worktree", style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        CreateWorktreeModeSelector(request.mode) { mode -> actions.onRequestChange(request.copy(mode = mode)) }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = request.existingBranchQuery,
            onValueChange = { query ->
                actions.onRequestChange(request.copy(existingBranchQuery = query, selectedExistingResult = null))
            },
            label = { Text("Search existing branches or PR number") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("existing-branch-search")
                .onPreviewKeyEvent(actions.onKeyEvent),
        )
        ExistingWorktreeLoadingIndicators(state)
        state.unsupportedPullRequestMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colors.error)
        }
        ExistingWorktreeRows(
            results = state.results,
            highlightedIndex = state.highlightedIndex,
            selectedResult = state.selectedResult,
            actions = ExistingWorktreeRowActions(
                onSelect = { result -> actions.onRequestChange(request.copy(selectedExistingResult = result)) },
                onKeyEvent = actions.onKeyEvent,
            ),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Button(onClick = actions.onConfirm, enabled = state.selectedResult != null) {
                Text("Use Existing")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = actions.onDismiss,
                modifier = Modifier.testTag("cancel-existing-worktree"),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ExistingWorktreeLoadingIndicators(state: ExistingWorktreeDialogState) {
    Column {
        if (state.isBranchLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading existing branches" },
            )
        }
        if (state.isPullRequestLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading pull request" },
            )
        }
    }
}

@Composable
private fun ExistingWorktreeRows(
    results: List<ExistingWorktreeResult>,
    highlightedIndex: Int?,
    selectedResult: ExistingWorktreeResult?,
    actions: ExistingWorktreeRowActions,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        itemsIndexed(results, key = { _, result -> existingWorktreeResultKey(result) }) { index, result ->
            val highlighted = index == highlightedIndex
            TextButton(
                onClick = { actions.onSelect(result) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (highlighted) {
                            MaterialTheme.colors.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colors.surface
                        },
                    )
                    .semantics { selected = highlighted }
                    .onPreviewKeyEvent(actions.onKeyEvent),
            ) {
                val selectedMarker = if (sameExistingWorktreeResult(selectedResult, result)) "Selected · " else ""
                Text(selectedMarker + existingWorktreeResultLabel(result))
            }
        }
    }
}

private sealed interface ExistingWorktreeKeyboardAction {
    data class Highlight(
        val index: Int,
    ) : ExistingWorktreeKeyboardAction

    data class Select(
        val result: ExistingWorktreeResult,
    ) : ExistingWorktreeKeyboardAction

    data object Confirm : ExistingWorktreeKeyboardAction
}

private fun handleExistingWorktreeKeyEvent(
    event: KeyEvent,
    results: List<ExistingWorktreeResult>,
    highlightedIndex: Int?,
    selectedResult: ExistingWorktreeResult?,
    callbacks: ExistingWorktreeKeyboardCallbacks,
): Boolean = when (
    val action = existingWorktreeKeyboardAction(event, results, highlightedIndex, selectedResult)
) {
    null -> false
    is ExistingWorktreeKeyboardAction.Highlight -> callbacks.onHighlight(action.index).let { true }
    is ExistingWorktreeKeyboardAction.Select -> callbacks.onSelect(action.result).let { true }
    ExistingWorktreeKeyboardAction.Confirm -> callbacks.onConfirm().let { true }
}

private fun KeyEvent.isEnterKey(): Boolean = key == Key.Enter || key == Key.NumPadEnter

private fun existingWorktreeKeyboardAction(
    event: KeyEvent,
    results: List<ExistingWorktreeResult>,
    highlightedIndex: Int?,
    selectedResult: ExistingWorktreeResult?,
): ExistingWorktreeKeyboardAction? {
    if (event.type != KeyEventType.KeyDown) return null

    return when (event.key) {
        Key.DirectionDown, Key.NumPadDirectionDown -> highlightResultBy(highlightedIndex, 1, results.size)

        Key.DirectionUp, Key.NumPadDirectionUp -> highlightResultBy(highlightedIndex, -1, results.size)

        Key.Enter, Key.NumPadEnter -> selectedResult?.let { ExistingWorktreeKeyboardAction.Confirm }
            ?: results.getOrNull(highlightedIndex ?: -1)?.let(ExistingWorktreeKeyboardAction::Select)

        else -> null
    }
}

private fun highlightResultBy(
    highlightedIndex: Int?,
    delta: Int,
    resultCount: Int,
): ExistingWorktreeKeyboardAction.Highlight? {
    if (resultCount == 0) return null
    val currentIndex = highlightedIndex ?: if (delta > 0) -1 else 0
    return ExistingWorktreeKeyboardAction.Highlight((currentIndex + delta + resultCount) % resultCount)
}

private fun Int.coerceToWorktreeResults(results: List<ExistingWorktreeResult>): Int? {
    if (results.isEmpty()) return null
    return coerceIn(results.indices)
}
