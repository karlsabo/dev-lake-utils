package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun EngHubActionPopup(
    expanded: Boolean,
    actions: List<EngHubAction>,
    onDismissRequest: () -> Unit,
    onDismissByKeyboard: () -> Unit,
) {
    var query by remember(expanded) { mutableStateOf("") }
    var highlightedIndex by remember(expanded) { mutableStateOf<Int?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val filteredActions = filterEngHubActions(actions, query)
    val contentState = ActionPopupContentState(
        query = query,
        actions = filteredActions,
        highlightedIndex = highlightedIndex.takeIfValidFor(filteredActions),
    )

    LaunchedEffect(expanded) {
        if (expanded) searchFocusRequester.requestFocus()
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        ActionPopupContent(
            state = contentState,
            searchFocusRequester = searchFocusRequester,
            callbacks = ActionPopupCallbacks(
                onQueryChange = { newQuery ->
                    query = newQuery
                    highlightedIndex = highlightedIndex.takeIfValidFor(
                        filterEngHubActions(actions, newQuery),
                    )
                },
                onHighlight = { highlightedIndex = it },
                onDismissByKeyboard = onDismissByKeyboard,
                onInvoke = { action ->
                    onDismissRequest()
                    action.onInvoke()
                },
            ),
        )
    }
}

private data class ActionPopupContentState(
    val query: String,
    val actions: List<EngHubAction>,
    val highlightedIndex: Int?,
)

private data class ActionPopupCallbacks(
    val onQueryChange: (String) -> Unit,
    val onHighlight: (Int?) -> Unit,
    val onDismissByKeyboard: () -> Unit,
    val onInvoke: (EngHubAction) -> Unit,
)

@Composable
private fun ActionPopupContent(
    state: ActionPopupContentState,
    searchFocusRequester: FocusRequester,
    callbacks: ActionPopupCallbacks,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .onPreviewKeyEvent { event ->
                handleActionPopupKeyEvent(
                    event = event,
                    actions = state.actions,
                    highlightedIndex = state.highlightedIndex,
                    callbacks = callbacks,
                )
            },
    ) {
        ActionSearchField(state.query, searchFocusRequester, callbacks.onQueryChange)
        ActionRows(state.actions, state.highlightedIndex, callbacks.onInvoke)
    }
}

@Composable
private fun ActionSearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
) {
    Box {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search actions…") },
            singleLine = true,
            modifier = Modifier
                .width(280.dp)
                .focusRequester(focusRequester)
                .testTag("action-search"),
        )
    }
}

@Composable
private fun ActionRows(
    actions: List<EngHubAction>,
    highlightedIndex: Int?,
    onInvoke: (EngHubAction) -> Unit,
) {
    actions.forEachIndexed { index, action ->
        val highlighted = index == highlightedIndex
        DropdownMenuItem(
            onClick = { onInvoke(action) },
            modifier = Modifier
                .background(
                    if (highlighted) {
                        MaterialTheme.colors.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colors.surface
                    },
                )
                .semantics { selected = highlighted },
        ) {
            Text(action.title)
        }
    }
}

private fun handleActionPopupKeyEvent(
    event: KeyEvent,
    actions: List<EngHubAction>,
    highlightedIndex: Int?,
    callbacks: ActionPopupCallbacks,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    return when (event.key) {
        Key.DirectionDown, Key.NumPadDirectionDown -> {
            callbacks.onHighlight(highlightedIndex.moveBy(1, actions.size))
            true
        }

        Key.DirectionUp, Key.NumPadDirectionUp -> {
            callbacks.onHighlight(highlightedIndex.moveBy(-1, actions.size))
            true
        }

        Key.Enter, Key.NumPadEnter -> {
            actions.getOrNull(highlightedIndex ?: -1)?.let(callbacks.onInvoke) != null
        }

        Key.Escape -> {
            callbacks.onDismissByKeyboard()
            true
        }

        else -> false
    }
}

private fun Int?.takeIfValidFor(actions: List<EngHubAction>): Int? = this?.takeIf(actions.indices::contains)

private fun Int?.moveBy(delta: Int, actionCount: Int): Int? = if (actionCount == 0) {
    null
} else {
    val currentIndex = this?.takeIf { it in 0 until actionCount }
    if (currentIndex == null) {
        if (delta > 0) 0 else actionCount - 1
    } else {
        (currentIndex + delta + actionCount) % actionCount
    }
}
