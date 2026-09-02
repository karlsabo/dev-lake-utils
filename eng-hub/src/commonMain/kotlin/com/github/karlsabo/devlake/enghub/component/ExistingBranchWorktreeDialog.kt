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

private data class ExistingBranchDialogState(
    val request: PendingCreateWorktree,
    val results: List<String>,
    val isLoading: Boolean,
    val highlightedIndex: Int?,
)

private data class ExistingBranchDialogActions(
    val onRequestChange: (PendingCreateWorktree) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    val onKeyEvent: (KeyEvent) -> Boolean,
)

private data class ExistingBranchKeyboardCallbacks(
    val onHighlight: (Int) -> Unit,
    val onSelect: (String) -> Unit,
    val onConfirm: () -> Unit,
)

private data class ExistingBranchRowActions(
    val onSelect: (String) -> Unit,
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
    val branches = discovery.takeIf { it.repoRootPath == request.repoRootPath }?.branches.orEmpty()
    val results = filterExistingBranches(branches, request.existingBranchQuery)
    var highlightedIndex by remember(request.repoRootPath, request.existingBranchQuery) { mutableIntStateOf(0) }
    val activeHighlightedIndex = highlightedIndex.coerceToBranchResults(results)
    val keyboardCallbacks = ExistingBranchKeyboardCallbacks(
        onHighlight = { index ->
            highlightedIndex = index
            if (request.selectedExistingBranch != results[index]) {
                onRequestChange(request.copy(selectedExistingBranch = null))
            }
        },
        onSelect = { branch -> onRequestChange(request.copy(selectedExistingBranch = branch)) },
        onConfirm = onConfirm,
    )

    ExistingBranchDialogBody(
        state = ExistingBranchDialogState(
            request = request,
            results = results,
            isLoading = discovery.repoRootPath == request.repoRootPath && discovery.isLoading,
            highlightedIndex = activeHighlightedIndex,
        ),
        actions = ExistingBranchDialogActions(
            onRequestChange = onRequestChange,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            onKeyEvent = { event ->
                handleExistingBranchKeyEvent(
                    event = event,
                    results = results,
                    highlightedIndex = activeHighlightedIndex,
                    selectedBranch = request.selectedExistingBranch,
                    callbacks = keyboardCallbacks,
                )
            },
        ),
    )
}

@Composable
private fun ExistingBranchDialogBody(
    state: ExistingBranchDialogState,
    actions: ExistingBranchDialogActions,
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
                actions.onRequestChange(request.copy(existingBranchQuery = query, selectedExistingBranch = null))
            },
            label = { Text("Search existing branches") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("existing-branch-search")
                .onPreviewKeyEvent(actions.onKeyEvent),
        )
        if (state.isLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading existing branches" },
            )
        }
        ExistingBranchRows(
            repoRootPath = request.repoRootPath,
            branches = state.results,
            highlightedIndex = state.highlightedIndex,
            selectedBranch = request.selectedExistingBranch,
            actions = ExistingBranchRowActions(
                onSelect = { branch -> actions.onRequestChange(request.copy(selectedExistingBranch = branch)) },
                onKeyEvent = actions.onKeyEvent,
            ),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Button(onClick = actions.onConfirm, enabled = request.selectedExistingBranch != null) {
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
private fun ExistingBranchRows(
    repoRootPath: String,
    branches: List<String>,
    highlightedIndex: Int?,
    selectedBranch: String?,
    actions: ExistingBranchRowActions,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        itemsIndexed(branches, key = { _, branch -> branch }) { index, branch ->
            val highlighted = index == highlightedIndex
            TextButton(
                onClick = { actions.onSelect(branch) },
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
                val selectedMarker = if (selectedBranch == branch) "Selected · " else ""
                Text("${selectedMarker}Branch · ${repositoryLabel(repoRootPath)} · $branch")
            }
        }
    }
}

private sealed interface ExistingBranchKeyboardAction {
    data class Highlight(
        val index: Int,
    ) : ExistingBranchKeyboardAction
    data class Select(
        val branch: String,
    ) : ExistingBranchKeyboardAction
    data object Confirm : ExistingBranchKeyboardAction
}

private fun handleExistingBranchKeyEvent(
    event: KeyEvent,
    results: List<String>,
    highlightedIndex: Int?,
    selectedBranch: String?,
    callbacks: ExistingBranchKeyboardCallbacks,
): Boolean = when (
    val action = existingBranchKeyboardAction(event, results, highlightedIndex, selectedBranch)
) {
    null -> false
    is ExistingBranchKeyboardAction.Highlight -> callbacks.onHighlight(action.index).let { true }
    is ExistingBranchKeyboardAction.Select -> callbacks.onSelect(action.branch).let { true }
    ExistingBranchKeyboardAction.Confirm -> callbacks.onConfirm().let { true }
}

private fun KeyEvent.isEnterKey(): Boolean = key == Key.Enter || key == Key.NumPadEnter

private fun existingBranchKeyboardAction(
    event: KeyEvent,
    results: List<String>,
    highlightedIndex: Int?,
    selectedBranch: String?,
): ExistingBranchKeyboardAction? {
    if (event.type != KeyEventType.KeyDown) return null

    return when (event.key) {
        Key.DirectionDown, Key.NumPadDirectionDown -> highlightBranchBy(highlightedIndex, 1, results.size)

        Key.DirectionUp, Key.NumPadDirectionUp -> highlightBranchBy(highlightedIndex, -1, results.size)

        Key.Enter, Key.NumPadEnter -> selectedBranch?.let { ExistingBranchKeyboardAction.Confirm }
            ?: results.getOrNull(highlightedIndex ?: -1)?.let(ExistingBranchKeyboardAction::Select)

        else -> null
    }
}

private fun highlightBranchBy(
    highlightedIndex: Int?,
    delta: Int,
    resultCount: Int,
): ExistingBranchKeyboardAction.Highlight? {
    if (resultCount == 0) return null
    val currentIndex = highlightedIndex ?: if (delta > 0) -1 else 0
    return ExistingBranchKeyboardAction.Highlight((currentIndex + delta + resultCount) % resultCount)
}

private fun Int.coerceToBranchResults(results: List<String>): Int? {
    if (results.isEmpty()) return null
    return coerceIn(results.indices)
}

private fun repositoryLabel(repoRootPath: String): String = repoRootPath
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
