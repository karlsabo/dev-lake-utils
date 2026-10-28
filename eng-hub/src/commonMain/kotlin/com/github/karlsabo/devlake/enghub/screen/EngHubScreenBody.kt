package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBinActions
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBinSection
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveFlightOverlay
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveFlightState
import com.github.karlsabo.devlake.enghub.component.WorktreeRowExitState

/** Root layout of the screen; the archive flight overlay sits above the sidebar and the panes. */
@Composable
internal fun EngHubScreenBody(
    state: EngHubScreenState,
    actions: EngHubScreenActions,
    exitState: WorktreeRowExitState,
    archiveFlight: WorktreeArchiveFlightState,
    onCreateWorktree: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            EngHubSidebar(
                selectedPane = state.selectedPane,
                paneAvailability = state.paneAvailability,
                archiveBin = WorktreeArchiveBinSection(
                    entries = state.archiveBinEntries,
                    actions = actions.archiveBinActions(),
                    onBoundsUpdate = archiveFlight.onBinBoundsUpdate,
                ),
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
                    onCreateWorktree = onCreateWorktree,
                )
                Spacer(modifier = Modifier.size(8.dp))
                EngHubPaneContent(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                    exitState = exitState,
                    archiveFlight = archiveFlight,
                )
            }
        }
        WorktreeArchiveFlightOverlay(state = archiveFlight, modifier = Modifier.fillMaxSize())
    }
}

internal fun EngHubScreenActions.archiveBinActions() = WorktreeArchiveBinActions(
    onUndo = onUndoQueuedWorktreeArchive,
    onRetry = onRetryFailedWorktreeArchive,
    onDismiss = onDismissFailedWorktreeArchive,
    onRequestForceConfirmation = onRequestForceArchiveLocalWorktree,
)
