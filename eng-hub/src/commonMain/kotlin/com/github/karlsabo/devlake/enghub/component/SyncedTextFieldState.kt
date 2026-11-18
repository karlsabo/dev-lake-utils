package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

private const val MAX_PENDING_EDITS = 64

/**
 * Keeps a text field's own text and caret authoritative while mirroring an owner-held value.
 *
 * Value-based text fields reset their text to whatever the owner passes on each recomposition while keeping
 * their own caret. When the owner's value lags behind fast typing (state flows, separate dialog windows), that
 * pairs newer caret positions with older text and characters land in the wrong place. This tracker lets the
 * field ignore lagging echoes of its own edits and only apply genuine owner changes.
 */
internal class TextFieldOwnerSync(
    initialValue: String,
) {
    private var ownerValue = initialValue
    private val pendingEdits = ArrayDeque<String>()

    /** Returns true when [text] is a local edit the owner has not been told about yet. */
    fun recordLocalText(text: String): Boolean {
        val latestKnownText = pendingEdits.lastOrNull() ?: ownerValue
        if (text == latestKnownText) return false
        if (pendingEdits.size == MAX_PENDING_EDITS) pendingEdits.removeFirst()
        pendingEdits.addLast(text)
        return true
    }

    /** Returns text the field should adopt, or null when [value] is unchanged or an echo of a local edit. */
    fun acceptOwnerValue(value: String): String? {
        if (value == ownerValue) return null
        ownerValue = value
        val echoIndex = pendingEdits.indexOf(value)
        val isEcho = echoIndex >= 0
        if (isEcho) repeat(echoIndex + 1) { pendingEdits.removeFirst() } else pendingEdits.clear()
        return value.takeUnless { isEcho }
    }
}

/** Returns field-owned text state that reports edits to [onValueChange] and adopts genuine changes to [value]. */
@Composable
internal fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val state = rememberTextFieldState(initialText = value)
    val ownerSync = remember(state) { TextFieldOwnerSync(value) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(state, ownerSync) {
        snapshotFlow { state.text.toString() }
            .collect { text -> if (ownerSync.recordLocalText(text)) currentOnValueChange(text) }
    }

    SideEffect {
        ownerSync.acceptOwnerValue(value)
            ?.takeIf { ownerText -> ownerText != state.text.toString() }
            ?.let(state::setTextAndPlaceCursorAtEnd)
    }

    return state
}
