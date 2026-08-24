package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions.onDraftChange,
            label = { Text("New setup command") },
            isError = state.error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-new"),
        )
        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(start = 16.dp).testTag("$tagPrefix-new-error"),
            )
        }
        state.commands.forEachIndexed { commandIndex, command ->
            SetupCommandRow(
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
        Button(
            onClick = { actions.onAddAt(state.commands.size) },
            modifier = Modifier.testTag("$tagPrefix-add-last"),
        ) {
            Text(if (state.commands.isEmpty()) "Add command" else "Add at end")
        }
    }
}

private data class SetupCommandRowActions(
    val onCommandChange: (String) -> Unit,
    val onAddBefore: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun SetupCommandRow(
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
            OutlinedTextField(
                value = command,
                onValueChange = actions.onCommandChange,
                label = { Text("Setup command") },
                isError = error != null,
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(tag),
            )
            Button(
                onClick = actions.onAddBefore,
                modifier = Modifier.testTag("$tag-add-before"),
            ) {
                Text("Add before")
            }
            Button(
                onClick = actions.onRemove,
                modifier = Modifier.testTag("$tag-remove"),
            ) {
                Text("Remove")
            }
        }
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(start = 16.dp).testTag("$tag-error"),
            )
        }
    }
}
