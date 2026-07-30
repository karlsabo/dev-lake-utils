package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.EngHubAction
import com.github.karlsabo.devlake.enghub.component.EngHubActionPopup
import com.github.karlsabo.devlake.enghub.component.ErrorDialog
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel

@Composable
fun EngHubScreen(
    viewModel: EngHubViewModel,
    settingsViewModel: EngHubSettingsViewModel,
) {
    var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
    val state = collectEngHubScreenState(viewModel, settingsViewModel, selectedPane)
    val actions = engHubScreenActions(
        viewModel = viewModel,
        settingsViewModel = settingsViewModel,
        onPaneSelected = { selectedPane = it },
    )

    state.actionError?.let { error ->
        ErrorDialog(message = error.message, onDismiss = actions.onClearActionError)
    }

    MaterialTheme {
        EngHubScreenContent(state = state, actions = actions)
    }
}

@Composable
private fun EngHubScreenContent(
    state: EngHubScreenState,
    actions: EngHubScreenActions,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        EngHubSidebar(
            selectedPane = state.selectedPane,
            onPaneSelect = actions.onPaneSelected,
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)),
        )
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            EngHubScreenHeader(
                selectedPane = state.selectedPane,
                onPaneSelect = actions.onPaneSelected,
            )
            Spacer(modifier = Modifier.size(8.dp))
            EngHubPaneContent(
                state = state,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun EngHubScreenHeader(
    selectedPane: EngHubPane,
    onPaneSelect: (EngHubPane) -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val actions = listOf(
        EngHubAction(title = "Settings") { onPaneSelect(EngHubPane.Settings) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selectedPane.label,
            style = MaterialTheme.typography.h5,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(
                onClick = { actionsExpanded = true },
                modifier = Modifier.semantics { contentDescription = "Open actions" },
            ) {
                Text(text = "⋯", style = MaterialTheme.typography.h5)
            }
            EngHubActionPopup(
                expanded = actionsExpanded,
                actions = actions,
                onDismissRequest = { actionsExpanded = false },
            )
        }
    }
}

@Composable
internal fun EngHubSidebar(
    selectedPane: EngHubPane,
    onPaneSelect: (EngHubPane) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxHeight().width(56.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EngHubPane.entries.filterNot { it == EngHubPane.Settings }.forEach { pane ->
                EngHubSidebarButton(
                    pane = pane,
                    selected = pane == selectedPane,
                    onClick = { onPaneSelect(pane) },
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        EngHubSidebarButton(
            pane = EngHubPane.Settings,
            selected = selectedPane == EngHubPane.Settings,
            onClick = { onPaneSelect(EngHubPane.Settings) },
        )
    }
}

@Composable
private fun EngHubSidebarButton(
    pane: EngHubPane,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colors.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(backgroundColor)
            .semantics { contentDescription = pane.label },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = pane.icon,
                color = contentColor,
                style = MaterialTheme.typography.button,
                textAlign = TextAlign.Center,
            )
        }
    }
}
