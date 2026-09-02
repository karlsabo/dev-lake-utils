package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.EngHubAction
import com.github.karlsabo.devlake.enghub.component.EngHubActionPopup
import com.github.karlsabo.devlake.enghub.component.ErrorDialog
import com.github.karlsabo.devlake.enghub.component.GlobalExistingBranchWorktreeDialog
import com.github.karlsabo.devlake.enghub.component.PendingGlobalCreateWorktree
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.launchAfterSettingsFlush
import kotlinx.coroutines.Job

@Composable
fun EngHubScreen(
    viewModel: EngHubViewModel,
    settingsViewModel: EngHubSettingsViewModel,
) {
    var selectedPane by remember(settingsViewModel) {
        mutableStateOf(initialEngHubPane(settingsViewModel.uiState.value))
    }
    val coroutineScope = rememberCoroutineScope()
    var paneNavigationJob by remember(settingsViewModel) { mutableStateOf<Job?>(null) }
    val state = collectEngHubScreenState(viewModel, settingsViewModel, selectedPane)
    val actions = engHubScreenActions(
        viewModel = viewModel,
        settingsViewModel = settingsViewModel,
        onPaneSelected = { pane ->
            paneNavigationJob?.cancel()
            paneNavigationJob = coroutineScope.launchAfterSettingsFlush(settingsViewModel) {
                selectedPane = availablePaneOrSettings(pane, settingsViewModel.uiState.value)
            }
        },
    )

    state.actionError?.let { error ->
        ErrorDialog(message = error.message, onDismiss = actions.onClearActionError)
    }

    MaterialTheme {
        EngHubScreenContent(state = state, actions = actions)
    }
}

internal fun initialEngHubPane(
    settings: EngHubSettingsUiState,
): EngHubPane = availablePaneOrSettings(EngHubPane.PullRequests, settings)

internal fun availablePaneOrSettings(
    requestedPane: EngHubPane,
    settings: EngHubSettingsUiState,
): EngHubPane = if (engHubPaneAvailability(settings).getValue(requestedPane).isEnabled) {
    requestedPane
} else {
    EngHubPane.Settings
}

@Composable
private fun EngHubScreenContent(
    state: EngHubScreenState,
    actions: EngHubScreenActions,
) {
    var pendingGlobalCreateWorktree by remember { mutableStateOf<PendingGlobalCreateWorktree?>(null) }

    LaunchedEffect(pendingGlobalCreateWorktree != null) {
        if (pendingGlobalCreateWorktree != null) actions.onDiscoverGlobalExistingBranches()
    }

    pendingGlobalCreateWorktree?.let { request ->
        GlobalExistingBranchWorktreeDialog(
            request = request,
            discovery = state.globalExistingBranchDiscovery,
            onRequestChange = { pendingGlobalCreateWorktree = it },
            onConfirm = {
                val selectedResult = requireNotNull(request.selectedExistingResult)
                pendingGlobalCreateWorktree = null
                actions.onCheckoutExistingBranch(selectedResult.repoRootPath, selectedResult.branch)
            },
            onDismiss = { pendingGlobalCreateWorktree = null },
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        EngHubSidebar(
            selectedPane = state.selectedPane,
            paneAvailability = state.paneAvailability,
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
                onCreateWorktree = { pendingGlobalCreateWorktree = PendingGlobalCreateWorktree() },
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
    onCreateWorktree: () -> Unit = {},
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    var restoreTriggerFocus by remember { mutableStateOf(false) }
    val actionTriggerFocusRequester = remember { FocusRequester() }
    val actions = listOf(
        EngHubAction(
            title = "Create Worktree",
            keywords = listOf("branch", "checkout", "worktree"),
            onInvoke = onCreateWorktree,
        ),
        EngHubAction(title = "Settings") { onPaneSelect(EngHubPane.Settings) },
    )

    LaunchedEffect(actionsExpanded, restoreTriggerFocus) {
        if (!actionsExpanded && restoreTriggerFocus) {
            actionTriggerFocusRequester.requestFocus()
            restoreTriggerFocus = false
        }
    }

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
                modifier = Modifier
                    .focusRequester(actionTriggerFocusRequester)
                    .semantics { contentDescription = "Open actions" },
            ) {
                Text(text = "⋯", style = MaterialTheme.typography.h5)
            }
            EngHubActionPopup(
                expanded = actionsExpanded,
                actions = actions,
                onDismissRequest = { actionsExpanded = false },
                onDismissByKeyboard = {
                    restoreTriggerFocus = true
                    actionsExpanded = false
                },
            )
        }
    }
}

@Composable
internal fun EngHubSidebar(
    selectedPane: EngHubPane,
    onPaneSelect: (EngHubPane) -> Unit,
    paneAvailability: Map<EngHubPane, EngHubPaneAvailability> =
        EngHubPane.entries.associateWith { EngHubPaneAvailability(isEnabled = true) },
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
                    availability = paneAvailability.getValue(pane),
                    onClick = { onPaneSelect(pane) },
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        EngHubSidebarButton(
            pane = EngHubPane.Settings,
            selected = selectedPane == EngHubPane.Settings,
            availability = paneAvailability.getValue(EngHubPane.Settings),
            onClick = { onPaneSelect(EngHubPane.Settings) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EngHubSidebarButton(
    pane: EngHubPane,
    selected: Boolean,
    availability: EngHubPaneAvailability,
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

    val button: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(backgroundColor),
        ) {
            IconButton(
                onClick = onClick,
                enabled = availability.isEnabled,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = pane.label },
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
    val disabledReason = availability.disabledReason
    if (disabledReason == null) {
        button()
    } else {
        TooltipArea(
            tooltip = {
                Surface(elevation = 4.dp) {
                    Text(disabledReason, modifier = Modifier.padding(8.dp))
                }
            },
            content = button,
        )
    }
}
