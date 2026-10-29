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
 * The archive feedback the screen is animating, plus the location reporters that feed it.
 * [flights] holds every chip currently in the air: archives queued while another chip flies get
 * their own chip immediately, so each keeps its own 220ms window. [binPulsePaths] carries the
 * outcome: an archive either flies and pulses on arrival, or cannot fly and pulses on queue.
 */
internal data class WorktreeArchiveFlightState(
    val flights: List<WorktreeArchiveFlight> = emptyList(),
    val onRowBoundsUpdate: (worktreePath: String, anchor: WorktreeArchiveFlightAnchor) -> Unit = { _, _ -> },
    val onBinBoundsUpdate: (Rect) -> Unit = {},
    val onFlightComplete: (worktreePath: String) -> Unit = {},
    val binPulsePaths: Set<String> = emptySet(),
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
 * Tracks row and bin geometry and starts a flight for every archive as it is newly queued, so
 * archives queued while another chip flies each get their own chip.
 * Coordinates are reported in root bounds, which is the space the overlay draws in.
 * The recycle bin pulses when a chip arrives; archives that cannot fly pulse on queue instead.
 */
@Composable
internal fun rememberWorktreeArchiveFlightState(
    queuedWorktreePaths: Set<String>,
    newlyQueuedWorktreePaths: Set<String>,
): WorktreeArchiveFlightState {
    // Deliberately not snapshot state: anchors are only read when a flight starts.
    val rowAnchors = remember { mutableMapOf<String, WorktreeArchiveFlightAnchor>() }
    var binBounds by remember { mutableStateOf<Rect?>(null) }
    var flights by remember { mutableStateOf(emptyList<WorktreeArchiveFlight>()) }
    var handledPaths by remember { mutableStateOf(emptySet<String>()) }
    var binPulsePaths by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(queuedWorktreePaths) {
        handledPaths = handledPaths intersect queuedWorktreePaths
        binPulsePaths = binPulsePaths intersect queuedWorktreePaths
    }

    LaunchedEffect(newlyQueuedWorktreePaths, binBounds) {
        val unhandledPaths = newlyQueuedWorktreePaths - handledPaths
        if (unhandledPaths.isEmpty()) return@LaunchedEffect
        val unanchoredPaths = unhandledPaths.filterNot { rowAnchors.containsKey(it) }.toSet()
        if (unanchoredPaths.isNotEmpty()) {
            // Decision 6: without captured row bounds there is no flight, so pulse on queue.
            handledPaths = handledPaths + unanchoredPaths
            binPulsePaths = binPulsePaths + unanchoredPaths
        }
        val destination = binBounds ?: return@LaunchedEffect
        // Every anchored path starts its own chip, so a mid-flight archive never replays later from
        // a row that has already collapsed.
        val startedFlights = (unhandledPaths - unanchoredPaths).mapNotNull { path ->
            rowAnchors[path]?.let { anchor ->
                WorktreeArchiveFlight(
                    worktreePath = path,
                    branch = anchor.branch,
                    origin = anchor.bounds,
                    destination = destination,
                )
            }
        }
        if (startedFlights.isEmpty()) return@LaunchedEffect
        handledPaths = handledPaths + startedFlights.map(WorktreeArchiveFlight::worktreePath)
        flights = flights + startedFlights
    }

    return WorktreeArchiveFlightState(
        flights = flights,
        onRowBoundsUpdate = { worktreePath, anchor -> rowAnchors[worktreePath] = anchor },
        onBinBoundsUpdate = { bounds -> binBounds = bounds },
        onFlightComplete = { worktreePath ->
            rowAnchors.remove(worktreePath)
            flights = flights.filterNot { it.worktreePath == worktreePath }
            binPulsePaths = binPulsePaths + worktreePath
        },
        binPulsePaths = binPulsePaths,
    )
}

/** Draws every in-flight archive chip on top of the screen content; inert once no flight is running. */
@Composable
internal fun WorktreeArchiveFlightOverlay(
    state: WorktreeArchiveFlightState,
    modifier: Modifier = Modifier,
) {
    if (state.flights.isEmpty()) return
    Box(modifier = modifier) {
        state.flights.forEach { flight ->
            key(flight.worktreePath) {
                WorktreeArchiveFlightChip(
                    flight = flight,
                    onFlightComplete = { state.onFlightComplete(flight.worktreePath) },
                )
            }
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
