package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import dev_lake_utils.shared_resources.generated.resources.Res
import dev_lake_utils.shared_resources.generated.resources.icon
import org.jetbrains.compose.resources.painterResource

private data class ConfirmationWorktreeDialogState(
    val title: String,
    val message: String,
    val worktreePath: String,
    val confirmText: String,
)

private data class ConfirmationWorktreeDialogActions(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun archiveWorktreeDialog(
    worktreePath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    confirmationWorktreeDialog(
        state = ConfirmationWorktreeDialogState(
            title = "Archive Worktree",
            message = "Remove this worktree and delete any leftover checkout directory?",
            worktreePath = worktreePath,
            confirmText = "Archive",
        ),
        actions = ConfirmationWorktreeDialogActions(
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        ),
    )
}

@Composable
internal fun forceArchiveWorktreeDialog(
    worktreePath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    confirmationWorktreeDialog(
        state = ConfirmationWorktreeDialogState(
            title = "Force Archive Worktree",
            message = "This worktree has local changes. Force removal will discard them.",
            worktreePath = worktreePath,
            confirmText = "Force Archive",
        ),
        actions = ConfirmationWorktreeDialogActions(
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        ),
    )
}

@Composable
private fun confirmationWorktreeDialog(
    state: ConfirmationWorktreeDialogState,
    actions: ConfirmationWorktreeDialogActions,
) {
    DialogWindow(
        onCloseRequest = actions.onDismiss,
        title = state.title,
        icon = painterResource(Res.drawable.icon),
        visible = true,
    ) {
        MaterialTheme {
            Surface {
                confirmationWorktreeDialogContent(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun confirmationWorktreeDialogContent(
    state: ConfirmationWorktreeDialogState,
    actions: ConfirmationWorktreeDialogActions,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        Text(text = state.title, style = MaterialTheme.typography.h6)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = state.message)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = state.worktreePath, style = MaterialTheme.typography.caption)
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Button(onClick = actions.onConfirm) {
                Text(state.confirmText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = actions.onDismiss) {
                Text("Cancel")
            }
        }
    }
}
