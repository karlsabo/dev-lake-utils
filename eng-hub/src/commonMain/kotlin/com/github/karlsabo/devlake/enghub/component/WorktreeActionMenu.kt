package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.DropdownMenuState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalWorktreeActionMenuButton(
    state: LocalWorktreeRowState,
    onClick: () -> Unit,
    onPositionUpdate: (Offset) -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = !state.isArchiving && !state.isRebasing,
        modifier = Modifier
            .size(32.dp)
            .onGloballyPositioned { coordinates -> onPositionUpdate(coordinates.boundsInWindow().bottomLeft) }
            .semantics { contentDescription = "Worktree actions for ${state.worktree.branch}" },
    ) {
        Text(text = "⋮", style = MaterialTheme.typography.button)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun LocalWorktreeActionMenu(
    state: LocalWorktreeRowState,
    actions: LocalWorktreeRowActions,
    menuState: DropdownMenuState,
) {
    DropdownMenu(
        state = menuState,
        onDismissRequest = { menuState.status = DropdownMenuState.Status.Closed },
        modifier = Modifier.testTag("worktree-action-menu"),
    ) {
        visibleWorktreeMenuActions(state.worktree, state.connectedPullRequest).forEach { action ->
            LocalWorktreeMenuItem(
                action = action,
                state = state,
                rowActions = actions,
                onMenuDismiss = { menuState.status = DropdownMenuState.Status.Closed },
            )
        }
    }
}

internal fun anchorRelativeOffset(
    anchorBoundsInWindow: Rect,
    positionInWindow: Offset,
): Offset = positionInWindow - anchorBoundsInWindow.topLeft

@Composable
private fun LocalWorktreeMenuItem(
    action: WorktreeMenuAction,
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    when (action) {
        WorktreeMenuAction.Open -> OpenWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.OpenPullRequest -> OpenPullRequestMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.CreateWorktree -> CreateWorktreeMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.RebaseOntoParent -> RebaseOntoParentMenuItem(state, rowActions, onMenuDismiss)
        WorktreeMenuAction.Archive -> ArchiveWorktreeMenuItem(state, rowActions, onMenuDismiss)
    }
}

@Composable
private fun OpenWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpen()
        },
        enabled = isWorktreeOpenEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text(setupActionLabel(defaultLabel = "Open", setupStatus = state.setupStatus))
    }
}

@Composable
private fun OpenPullRequestMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    val pullRequest = requireNotNull(state.connectedPullRequest)
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpenPullRequest(pullRequest.htmlUrl)
        },
    ) {
        Text("Open PR in web")
    }
}

@Composable
private fun CreateWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onOpenCreateWorktreeDialog()
        },
        enabled = isWorktreeCreateEnabled(state.worktree, state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Create worktree")
    }
}

@Composable
private fun RebaseOntoParentMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onRebaseOntoParent()
        },
        enabled = isWorktreeRebaseEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Rebase onto parent")
    }
}

@Composable
private fun ArchiveWorktreeMenuItem(
    state: LocalWorktreeRowState,
    rowActions: LocalWorktreeRowActions,
    onMenuDismiss: () -> Unit,
) {
    DropdownMenuItem(
        onClick = {
            onMenuDismiss()
            rowActions.onArchive()
        },
        enabled = isWorktreeArchiveEnabled(state.setupStatus, state.isArchiving, state.isRebasing),
    ) {
        Text("Archive")
    }
}
