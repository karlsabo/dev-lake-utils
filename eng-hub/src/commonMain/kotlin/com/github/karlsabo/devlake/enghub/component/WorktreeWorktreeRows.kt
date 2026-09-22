package com.github.karlsabo.devlake.enghub.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.contextMenuOpenDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeSetupStatus

private const val WORKTREE_ROW_BASE_INDENT_DP = 16
private const val WORKTREE_ROW_CHILD_INDENT_DP = 24

internal fun worktreeRowIndentDp(nestingDepth: Int): Int {
    val childIndentDp = WORKTREE_ROW_CHILD_INDENT_DP * nestingDepth.coerceAtLeast(0)
    return WORKTREE_ROW_BASE_INDENT_DP + childIndentDp
}

internal data class LocalWorktreeRowActions(
    val onOpen: () -> Unit,
    val onOpenPullRequest: (String) -> Unit,
    val onArchive: () -> Unit,
    val onOpenCreateWorktreeDialog: () -> Unit,
    val onUpdateFromOrigin: () -> Unit,
    val onRebaseOntoParent: () -> Unit,
    val onMergeOntoParent: () -> Unit,
)

private data class WorktreeRowContentActions(
    val onOpen: () -> Unit,
    val onArchive: () -> Unit,
    val onUpdateFromOrigin: () -> Unit,
    val onRebaseOntoParent: () -> Unit,
    val onMergeOntoParent: () -> Unit,
    val onMenuButtonClick: () -> Unit,
    val onMenuButtonPositionUpdate: (Offset) -> Unit,
    val onOpenPullRequest: (String) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
internal fun LocalWorktreeRow(
    state: LocalWorktreeRowState,
    actions: LocalWorktreeRowActions,
) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val backgroundColor = if (isHovered) {
        MaterialTheme.colors.onSurface.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val menuState = remember(state.worktree.path) { DropdownMenuState() }
    var menuAnchorBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var overflowPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .contextMenuOpenDetector(menuState)
            .onGloballyPositioned { menuAnchorBoundsInWindow = it.boundsInWindow() },
    ) {
        WorktreeRowContent(
            state = state,
            hoverInteractionSource = hoverInteractionSource,
            backgroundColor = backgroundColor,
            actions = WorktreeRowContentActions(
                onOpen = actions.onOpen,
                onArchive = actions.onArchive,
                onUpdateFromOrigin = actions.onUpdateFromOrigin,
                onRebaseOntoParent = actions.onRebaseOntoParent,
                onMergeOntoParent = actions.onMergeOntoParent,
                onMenuButtonClick = {
                    val anchorRelativePosition = anchorRelativeOffset(
                        anchorBoundsInWindow = menuAnchorBoundsInWindow,
                        positionInWindow = overflowPositionInWindow,
                    )
                    menuState.status = DropdownMenuState.Status.Open(anchorRelativePosition)
                },
                onMenuButtonPositionUpdate = { overflowPositionInWindow = it },
                onOpenPullRequest = actions.onOpenPullRequest,
            ),
        )
        LocalWorktreeActionMenu(state = state, actions = actions, menuState = menuState)
    }
}

@Composable
private fun WorktreeRowContent(
    state: LocalWorktreeRowState,
    hoverInteractionSource: MutableInteractionSource,
    backgroundColor: Color,
    actions: WorktreeRowContentActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .hoverable(hoverInteractionSource)
            .testTag("worktree-row-${state.worktree.branch}")
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(worktreeRowIndentDp(state.nestingDepth).dp))
        WorktreeDirtyIndicator(state.worktree)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.worktree.branch,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2,
        )
        ConnectedPullRequestDetails(
            state = state,
            onOpenPullRequest = actions.onOpenPullRequest,
            modifier = Modifier.weight(1f),
        )
        WorktreeRebaseNeededIndicator(state.worktree)
        WorktreeProgressLabels(
            state.setupStatus,
            state.isArchiving,
            state.isUpdating,
            state.isRebasing,
            state.isMerging,
        )
        UpdateWorktreeShortcut(state = state, onUpdateFromOrigin = actions.onUpdateFromOrigin)
        RebaseWorktreeShortcut(state = state, onRebaseOntoParent = actions.onRebaseOntoParent)
        MergeWorktreeShortcut(state = state, onMergeOntoParent = actions.onMergeOntoParent)
        OpenWorktreeShortcut(state = state, onOpen = actions.onOpen)
        ArchiveWorktreeShortcut(state = state, onArchive = actions.onArchive)
        LocalWorktreeActionMenuButton(
            state = state,
            onClick = actions.onMenuButtonClick,
            onPositionUpdate = actions.onMenuButtonPositionUpdate,
        )
    }
}

@Composable
private fun ConnectedPullRequestDetails(
    state: LocalWorktreeRowState,
    onOpenPullRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullRequest = state.connectedPullRequest ?: return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = "PR #${pullRequest.number} · ${pullRequest.title}"
        val annotatedLabel = buildAnnotatedString {
            withLink(
                LinkAnnotation.Url(
                    url = pullRequest.htmlUrl,
                    linkInteractionListener = LinkInteractionListener {
                        onOpenPullRequest(pullRequest.htmlUrl)
                    },
                ),
            ) {
                append(label)
            }
        }
        Text(
            text = annotatedLabel,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Open $label in browser" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption,
        )
        Spacer(modifier = Modifier.width(6.dp))
        StatusBadge(status = pullRequest.ciStatus)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${pullRequest.ciSummaryText} · ${pullRequest.reviewSummaryText}",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption,
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun WorktreeDirtyIndicator(worktree: LocalWorktreeUiState) {
    Text(
        text = if (worktree.isDirty) "🟡" else "🟢",
        modifier = Modifier.semantics {
            contentDescription = if (worktree.isDirty) "Dirty worktree" else "Clean worktree"
        },
        style = MaterialTheme.typography.body2,
    )
}

@Composable
private fun WorktreeRebaseNeededIndicator(worktree: LocalWorktreeUiState) {
    if (worktree.needsRebase) {
        Row {
            Text(
                text = "Rebase needed",
                modifier = Modifier.semantics { contentDescription = "Rebase needed" },
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun WorktreeProgressLabels(
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
    isUpdating: Boolean,
    isRebasing: Boolean,
    isMerging: Boolean,
) {
    Row {
        setupStatus?.let {
            Text(text = it.setupStatusLabel(), style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isArchiving) {
            Text(text = "Archiving...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isUpdating) {
            Text(text = "Updating...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isRebasing) {
            Text(text = "Rebasing...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isMerging) {
            Text(text = "Merging...", style = MaterialTheme.typography.caption)
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
