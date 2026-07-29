package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun EngHubActionPopup(
    expanded: Boolean,
    actions: List<EngHubAction>,
    onDismissRequest: () -> Unit,
) {
    var query by remember(expanded) { mutableStateOf("") }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.width(280.dp)) {
            Box {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search actions…") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp),
                )
            }
            filterEngHubActions(actions, query).forEach { action ->
                DropdownMenuItem(
                    onClick = {
                        onDismissRequest()
                        action.onInvoke()
                    },
                ) {
                    Text(action.title)
                }
            }
        }
    }
}
