package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
