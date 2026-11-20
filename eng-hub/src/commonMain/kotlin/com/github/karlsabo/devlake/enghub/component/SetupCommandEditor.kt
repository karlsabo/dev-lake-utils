package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val NO_SETUP_COMMANDS_HINT = "No setup commands. New worktrees are used as-is."
private const val INSERT_HINT = "Type a command below, then add it at the end or use “Add before” on a row."
private val CommandNumberWidth = 24.dp

internal data class SetupCommandEditorState(
    val repositoryIndex: Int,
    val commands: List<String>,
    val draft: String,
    val error: String?,
    val commandEditErrors: Map<Int, String> = emptyMap(),
)

internal data class SetupCommandEditorActions(
    val onDraftChange: (String) -> Unit = {},
    val onAddAt: (Int) -> Unit = {},
    val onCommandChange: (commandIndex: Int, command: String) -> Unit = { _, _ -> },
    val onRemove: (commandIndex: Int) -> Unit = {},
)

/** Numbered commands in run order, followed by the draft that any insertion action consumes. */
@Composable
internal fun SetupCommandEditor(
    state: SetupCommandEditorState,
    actions: SetupCommandEditorActions,
    modifier: Modifier = Modifier,
) {
    val tagPrefix = "repository-${state.repositoryIndex}-command"
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (state.commands.isEmpty()) NO_SETUP_COMMANDS_HINT else INSERT_HINT,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        state.commands.forEachIndexed { commandIndex, command ->
            SetupCommandRow(
                number = commandIndex + 1,
                command = command,
                error = state.commandEditErrors[commandIndex],
                tag = "$tagPrefix-$commandIndex",
                actions = SetupCommandRowActions(
                    onCommandChange = { replacement -> actions.onCommandChange(commandIndex, replacement) },
                    onAddBefore = { actions.onAddAt(commandIndex) },
                    onRemove = { actions.onRemove(commandIndex) },
                ),
            )
        }
        NewSetupCommandRow(state = state, actions = actions, tagPrefix = tagPrefix)
    }
}

@Composable
private fun NewSetupCommandRow(
    state: SetupCommandEditorState,
    actions: SetupCommandEditorActions,
    tagPrefix: String,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                state = rememberSyncedTextFieldState(state.draft, actions.onDraftChange),
                label = { Text("New setup command") },
                isError = state.error != null,
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.weight(1f).testTag("$tagPrefix-new"),
            )
            Button(
                onClick = { actions.onAddAt(state.commands.size) },
                modifier = Modifier.testTag("$tagPrefix-add-last"),
            ) {
                Text(if (state.commands.isEmpty()) "Add command" else "Add at end")
            }
        }
        state.error?.let { CommandError(it, "$tagPrefix-new-error") }
    }
}

private data class SetupCommandRowActions(
    val onCommandChange: (String) -> Unit,
    val onAddBefore: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun SetupCommandRow(
    number: Int,
    command: String,
    error: String?,
    tag: String,
    actions: SetupCommandRowActions,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$number",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(CommandNumberWidth),
            )
            OutlinedTextField(
                state = rememberSyncedTextFieldState(command, actions.onCommandChange),
                label = { Text("Setup command") },
                isError = error != null,
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.weight(1f).testTag(tag),
            )
            SecondaryButton(text = "Add before", tag = "$tag-add-before", onClick = actions.onAddBefore)
            DestructiveButton(text = "Remove", tag = "$tag-remove", onClick = actions.onRemove)
        }
        error?.let { CommandError(it, "$tag-error") }
    }
}

@Composable
private fun CommandError(error: String, tag: String) {
    Text(
        text = error,
        color = MaterialTheme.colors.error,
        style = MaterialTheme.typography.caption,
        modifier = Modifier.padding(start = CommandNumberWidth + 24.dp).testTag(tag),
    )
}
