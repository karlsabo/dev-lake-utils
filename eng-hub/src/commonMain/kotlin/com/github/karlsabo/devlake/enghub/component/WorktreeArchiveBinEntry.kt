package com.github.karlsabo.devlake.enghub.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BIN_PULSE_DURATION_MS = 160
private const val BIN_PULSE_SCALE = 1.25f
private const val FORCE_REVIEW_BADGE_DESCRIPTION = "Force removal review required"

internal data class WorktreeArchiveBinEntry(
    val repository: String,
    val branch: String,
    val remainingSeconds: Long,
    val worktreePath: String,
    val queueId: String = "",
    val isRemoving: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val needsForceConfirmation: Boolean = false,
)

internal data class WorktreeArchiveBinActions(
    val onUndo: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onRequestForceConfirmation: (String) -> Unit = {},
)

/** Everything the sidebar needs to render and locate the recycle bin. */
internal data class WorktreeArchiveBinSection(
    val entries: List<WorktreeArchiveBinEntry> = emptyList(),
    val actions: WorktreeArchiveBinActions = WorktreeArchiveBinActions(),
    val onBoundsUpdate: (Rect) -> Unit = {},
    /** Worktree paths whose archive just reached the bin, or queued without a flight. */
    val pulseWorktreePaths: Set<String> = emptySet(),
)

@Composable
internal fun WorktreeArchiveBin(
    entries: List<WorktreeArchiveBinEntry>,
    modifier: Modifier = Modifier,
    actions: WorktreeArchiveBinActions = WorktreeArchiveBinActions(),
    onBoundsUpdate: (Rect) -> Unit = {},
    pulseWorktreePaths: Set<String> = emptySet(),
) {
    var expanded by remember { mutableStateOf(false) }
    val pulseScale = remember { Animatable(1f) }
    val pulseMutex = remember { Mutex() }
    val binScope = rememberCoroutineScope()
    entries.filter { it.worktreePath in pulseWorktreePaths }.forEach { entry ->
        ArchiveBinPulse(
            queueId = entry.queueId,
            pulseScale = pulseScale,
            pulseMutex = pulseMutex,
            scope = binScope,
        )
    }
    val needsForceReview = entries.any { it.needsForceConfirmation }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(40.dp)
                .onGloballyPositioned { coordinates -> onBoundsUpdate(coordinates.boundsInRoot()) }
                .graphicsLayer {
                    scaleX = pulseScale.value
                    scaleY = pulseScale.value
                }
                .semantics { contentDescription = "Recycle bin (${entries.size})" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = "♻", style = MaterialTheme.typography.button)
                if (needsForceReview) ForceReviewBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Recycle bin", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.size(8.dp))
                if (entries.isEmpty()) {
                    Text("No worktrees queued for archive")
                } else {
                    entries.forEach { entry ->
                        key(entry.worktreePath) {
                            WorktreeArchiveBinEntryRow(entry, actions)
                        }
                    }
                }
            }
        }
    }
}

/**
 * High-contrast marker drawn over the closed recycle bin while a worktree still needs force-removal review.
 * The description merges into the bin button so the requirement is announced with the icon itself.
 */
@Composable
private fun ForceReviewBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colors.error, CircleShape)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = "!",
            color = MaterialTheme.colors.onError,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = FORCE_REVIEW_BADGE_DESCRIPTION },
        )
    }
}

/**
 * Plays one 160ms-out-and-back scale pulse for an archive that has landed in the bin.
 * Pulses queue on [pulseMutex] so back-to-back arrivals stay visible instead of overlapping.
 */
@Composable
private fun ArchiveBinPulse(
    queueId: String,
    pulseScale: Animatable<Float, AnimationVector1D>,
    pulseMutex: Mutex,
    scope: CoroutineScope,
) {
    key(queueId) {
        DisposableEffect(queueId) {
            var started = false
            val pulse = scope.launch {
                pulseMutex.withLock {
                    started = true
                    pulseScale.animateTo(BIN_PULSE_SCALE, tween(BIN_PULSE_DURATION_MS))
                    pulseScale.animateTo(1f, tween(BIN_PULSE_DURATION_MS))
                }
            }
            onDispose {
                // Once started, the bin owns the return to rest even if this entry is undone.
                if (!started) pulse.cancel()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorktreeArchiveBinEntryRow(
    entry: WorktreeArchiveBinEntry,
    actions: WorktreeArchiveBinActions,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(entry.branch, style = MaterialTheme.typography.body1)
        Text(entry.repository, style = MaterialTheme.typography.caption)
        if (entry.needsForceConfirmation) {
            Text("Confirmation required", style = MaterialTheme.typography.caption)
            TextButton(onClick = { actions.onRequestForceConfirmation(entry.worktreePath) }) {
                Text("Review force removal")
            }
            TextButton(onClick = { actions.onDismiss(entry.worktreePath) }) { Text("Dismiss") }
        } else if (entry.isFailed) {
            Text("Removal failed", style = MaterialTheme.typography.caption)
            entry.errorMessage?.let { Text(it, style = MaterialTheme.typography.caption) }
            TextButton(onClick = { actions.onRetry(entry.worktreePath) }) { Text("Retry") }
            TextButton(onClick = { actions.onDismiss(entry.worktreePath) }) { Text("Dismiss") }
        } else if (entry.isRemoving) {
            Text("Being removed", style = MaterialTheme.typography.caption)
            TooltipArea(
                tooltip = {
                    Surface(elevation = 4.dp) {
                        Text(
                            "This worktree is being removed and can no longer be canceled.",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                },
            ) {
                TextButton(onClick = {}, enabled = false) {
                    Text("Undo")
                }
            }
        } else {
            Text("${entry.remainingSeconds} seconds remaining", style = MaterialTheme.typography.caption)
            TextButton(onClick = { actions.onUndo(entry.worktreePath) }) {
                Text("Undo")
            }
        }
    }
}
