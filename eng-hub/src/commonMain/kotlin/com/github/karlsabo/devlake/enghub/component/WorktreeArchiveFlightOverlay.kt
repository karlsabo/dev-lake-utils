package com.github.karlsabo.devlake.enghub.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal const val WORKTREE_ARCHIVE_FLIGHT_DURATION_MS = 220

/** Share of its starting size the chip retains when it reaches the bin. */
private const val WORKTREE_ARCHIVE_FLIGHT_END_SCALE = 0.35f

/** Geometry of a worktree row captured while the row is still fully visible. */
internal data class WorktreeArchiveFlightAnchor(
    val branch: String,
    val bounds: Rect,
)

/** One archive chip travelling from a worktree row to the recycle bin. */
internal data class WorktreeArchiveFlight(
    val worktreePath: String,
    val branch: String,
    val origin: Rect,
    val destination: Rect,
)

/**
 * The single archive flight the screen is animating, plus the location reporters that feed it.
 * Concurrent flights are intentionally unsupported; a queued archive waits without a chip instead.
 */
internal data class WorktreeArchiveFlightState(
    val flight: WorktreeArchiveFlight? = null,
    val onRowBoundsUpdate: (worktreePath: String, anchor: WorktreeArchiveFlightAnchor) -> Unit = { _, _ -> },
    val onBinBoundsUpdate: (Rect) -> Unit = {},
    val onFlightComplete: () -> Unit = {},
)

/** Cosine ease-in-out: slow departure, fast middle, slow arrival on the bin. */
internal val SinusoidalEaseInOut = Easing { fraction ->
    val clamped = fraction.coerceIn(0f, 1f)
    ((1.0 - cos(PI * clamped)) / 2.0).toFloat()
}

/** Interpolated chip center for a straight line between the row and the bin. */
internal fun worktreeArchiveFlightPosition(flight: WorktreeArchiveFlight, progress: Float): Offset {
    val start = flight.origin.center
    val end = flight.destination.center
    return Offset(
        x = start.x + (end.x - start.x) * progress,
        y = start.y + (end.y - start.y) * progress,
    )
}

/** Scale shrinks the chip towards the recycle bin icon as it travels. */
internal fun worktreeArchiveFlightScale(progress: Float): Float {
    val shrink = (1f - WORKTREE_ARCHIVE_FLIGHT_END_SCALE) * progress
    return 1f - shrink
}

/**
 * Tracks row and bin geometry and starts a flight when an archive is newly queued.
 * Coordinates are reported in root bounds, which is the space the overlay draws in.
 */
@Composable
internal fun rememberWorktreeArchiveFlightState(
    queuedWorktreePaths: Set<String>,
    newlyQueuedWorktreePaths: Set<String>,
): WorktreeArchiveFlightState {
    // Deliberately not snapshot state: anchors are only read when a flight starts.
    val rowAnchors = remember { mutableMapOf<String, WorktreeArchiveFlightAnchor>() }
    var binBounds by remember { mutableStateOf<Rect?>(null) }
    var flight by remember { mutableStateOf<WorktreeArchiveFlight?>(null) }
    var handledPaths by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(queuedWorktreePaths) {
        handledPaths = handledPaths intersect queuedWorktreePaths
    }

    LaunchedEffect(newlyQueuedWorktreePaths, binBounds, flight) {
        if (flight != null) return@LaunchedEffect
        val destination = binBounds ?: return@LaunchedEffect
        val source = newlyQueuedWorktreePaths
            .filterNot { it in handledPaths }
            .firstNotNullOfOrNull { path -> rowAnchors[path]?.let { path to it } }
            ?: return@LaunchedEffect
        val (worktreePath, anchor) = source
        handledPaths = handledPaths + worktreePath
        flight = WorktreeArchiveFlight(
            worktreePath = worktreePath,
            branch = anchor.branch,
            origin = anchor.bounds,
            destination = destination,
        )
    }

    return WorktreeArchiveFlightState(
        flight = flight,
        onRowBoundsUpdate = { worktreePath, anchor -> rowAnchors[worktreePath] = anchor },
        onBinBoundsUpdate = { bounds -> binBounds = bounds },
        onFlightComplete = {
            flight?.let { completed -> rowAnchors.remove(completed.worktreePath) }
            flight = null
        },
    )
}

/** Draws the archive chip on top of the screen content; it is inert once no flight is running. */
@Composable
internal fun WorktreeArchiveFlightOverlay(
    state: WorktreeArchiveFlightState,
    modifier: Modifier = Modifier,
) {
    val flight = state.flight ?: return
    Box(modifier = modifier) {
        key(flight.worktreePath) {
            WorktreeArchiveFlightChip(flight = flight, onFlightComplete = state.onFlightComplete)
        }
    }
}

@Composable
private fun WorktreeArchiveFlightChip(
    flight: WorktreeArchiveFlight,
    onFlightComplete: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val currentOnComplete by rememberUpdatedState(onFlightComplete)
    LaunchedEffect(flight) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = WORKTREE_ARCHIVE_FLIGHT_DURATION_MS,
                easing = SinusoidalEaseInOut,
            ),
        )
        currentOnComplete()
    }
    val easedProgress = progress.value
    val center = worktreeArchiveFlightPosition(flight, easedProgress)
    val scale = worktreeArchiveFlightScale(easedProgress)
    val laneSize = with(LocalDensity.current) {
        flight.origin.width.toDp() to flight.origin.height.toDp()
    }
    // A row-sized transparent lane centers the chip without measuring the chip itself.
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (center.x - flight.origin.width / 2f).roundToInt(),
                    y = (center.y - flight.origin.height / 2f).roundToInt(),
                )
            }
            .size(width = laneSize.first, height = laneSize.second)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .testTag("archive-flight-chip-${flight.branch}"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            elevation = 6.dp,
            color = MaterialTheme.colors.primary,
            contentColor = MaterialTheme.colors.onPrimary,
        ) {
            Text(
                text = flight.branch,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.body2,
            )
        }
    }
}
