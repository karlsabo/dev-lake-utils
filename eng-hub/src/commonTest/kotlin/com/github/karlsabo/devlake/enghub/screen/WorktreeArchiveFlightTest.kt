package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.component.ForceArchiveWorktreeActions
import com.github.karlsabo.devlake.enghub.component.GlobalExistingBranchDiscoveryUiState
import com.github.karlsabo.devlake.enghub.component.LocalWorktreeActions
import com.github.karlsabo.devlake.enghub.component.NotificationActions
import com.github.karlsabo.devlake.enghub.component.SinusoidalEaseInOut
import com.github.karlsabo.devlake.enghub.component.WORKTREE_ARCHIVE_FLIGHT_DURATION_MS
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBinEntry
import com.github.karlsabo.devlake.enghub.component.WorktreePanelActions
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.devlake.enghub.component.worktreeArchiveFlightScale
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_FRAME_MS = 16f
private const val FRAME_PROGRESS = TEST_FRAME_MS / WORKTREE_ARCHIVE_FLIGHT_DURATION_MS
private const val ROW_TAG = "worktree-row-feature/login"
private const val CHIP_TAG = "archive-flight-chip-feature/login"
private const val SECOND_ROW_TAG = "worktree-row-feature/signup"
private const val SECOND_CHIP_TAG = "archive-flight-chip-feature/signup"
private const val LOGIN_BRANCH = "feature/login"
private const val LOGIN_PATH = "/repos/widgets-feature-login"
private const val SECOND_BRANCH = "feature/signup"
private const val SECOND_PATH = "/repos/widgets-feature-signup"
private const val GHOST_BRANCH = "feature/ghost"
private const val GHOST_PATH = "/repos/widgets-feature-ghost"
private const val GHOST_CHIP_TAG = "archive-flight-chip-feature/ghost"

/** Frames the second archive waits before it is queued: about 100ms into the first chip's flight. */
private const val SECOND_ARCHIVE_DELAY_FRAMES = 6

/** Frames the bin is sampled for: long enough to cover two serialized 320ms out-and-back pulses. */
private const val BIN_PULSE_SAMPLE_FRAMES = 50

/** Ignores capture noise that is too short to be a pulse hump. */
private const val MIN_BIN_PULSE_HUMP_FRAMES = 3

/**
 * The chip's shadow reaches the bin icon well before the chip itself does, so bin pixels are only
 * trusted while the chip's center is still this far away. The capture cannot separate the two.
 */
private const val CHIP_CLEAR_OF_BIN_PX = 150f
private const val MAX_CHIP_START_FRAMES = 5
private const val MAX_CHIP_FLIGHT_FRAMES = 30
private const val ARCHIVE_SHORTCUT_DESCRIPTION = "Archive worktree feature/login"

class WorktreeArchiveFlightTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveMenuClickFliesChipFromRowToBinAlongSinusoidWhileRowCollapses() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val rowCenter = onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot.center
        val binCenter = onNodeWithContentDescription("Recycle bin (0)").fetchSemanticsNode().boundsInRoot.center

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        mainClock.advanceTimeByFrame()
        // The real row collapses on its own 220ms schedule, concurrently with the chip.
        onNodeWithTag(ROW_TAG).assertExists()

        val samples = recordArchiveFlightChip(CHIP_TAG)
        assertTrue(samples.widths.last() < samples.widths.first() / 2f, "chip shrinks: ${samples.widths}")
        onNodeWithTag(CHIP_TAG).assertDoesNotExist()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithTag(ROW_TAG).assertDoesNotExist()

        assertSinusoidalFlight(samples.centers, origin = rowCenter, destination = binCenter)
    }

    /**
     * Acceptance (story 3): the row's archive shortcut and the row menu are two handles on the same
     * `LocalWorktreeRowActions.onArchive`, so the shortcut must fly the same chip rather than quietly
     * collapsing the row. This test pins the shortcut trigger, not just the menu trigger.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveShortcutFliesTheSameChipFromRowToBinWhileRowCollapses() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val rowCenter = onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot.center
        val binCenter = onNodeWithContentDescription("Recycle bin (0)").fetchSemanticsNode().boundsInRoot.center

        // The shortcut sits on the row itself, so acting on a focused row is this click.
        onNodeWithContentDescription(ARCHIVE_SHORTCUT_DESCRIPTION).performClick()
        mainClock.advanceTimeByFrame()
        // The archive is queued, and the row still collapses on its own 220ms schedule.
        onNodeWithContentDescription("Recycle bin (1)").assertExists()
        onNodeWithTag(ROW_TAG).assertExists()

        val samples = recordArchiveFlightChip(CHIP_TAG)
        assertTrue(samples.widths.last() < samples.widths.first() / 2f, "chip shrinks: ${samples.widths}")
        onNodeWithTag(CHIP_TAG).assertDoesNotExist()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithTag(ROW_TAG).assertDoesNotExist()

        assertSinusoidalFlight(samples.centers, origin = rowCenter, destination = binCenter)
    }

    /**
     * Acceptance (story 4): a second archive queued while the first chip is still in the air gets a
     * chip of its own. Both fly concurrently and vanish on their own 220ms schedules, so the second
     * neither waits for the first to land nor replays later from a row that has already collapsed.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archivesQueuedWhileAChipIsFlyingAnimateIndependentlyOnTheirOwnSchedules() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(
            screenStateWithWorktrees(
                TestWorktree(LOGIN_BRANCH, LOGIN_PATH),
                TestWorktree(SECOND_BRANCH, SECOND_PATH),
            ),
        )
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val binCenter = onNodeWithContentDescription("Recycle bin (0)").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag(SECOND_ROW_TAG).assertExists()

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        advanceFramesUntilTagExists(CHIP_TAG)

        // Follow both chips frame by frame; the second archive is queued ~100ms into the first flight.
        val frames = recordConcurrentArchiveFlight(
            firstTag = CHIP_TAG,
            secondTag = SECOND_CHIP_TAG,
            queueSecondArchive = { screenState = screenState.queuingArchive(SECOND_BRANCH, SECOND_PATH) },
        )
        assertIndependentChipSchedules(frames, binCenter)

        // Watch well past both flights: neither archive may replay a delayed chip.
        repeat(MAX_CHIP_FLIGHT_FRAMES) {
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CHIP_TAG).assertCountEquals(0)
            onAllNodesWithTag(SECOND_CHIP_TAG).assertCountEquals(0)
        }
        // Both rows collapse on their own schedule, regardless of the chips.
        onNodeWithTag(ROW_TAG).assertDoesNotExist()
        onNodeWithTag(SECOND_ROW_TAG).assertDoesNotExist()
    }

    private fun assertIndependentChipSchedules(frames: ConcurrentFlightFrames, binCenter: Offset) {
        val firstFrames = frames.firstFrames
        val secondFrames = frames.secondFrames
        assertTrue(
            firstFrames.size in 13..18,
            "the first chip flies for one 220ms window: ${firstFrames.size} frames",
        )
        assertTrue(
            secondFrames.size in 13..18,
            "the second chip flies for its own 220ms window: ${secondFrames.size} frames",
        )
        val overlapFrames = firstFrames.count { it in secondFrames }
        assertTrue(overlapFrames >= 5, "both chips are in the air together for $overlapFrames frames")

        // The second chip started and finished ~100ms after the first, so the two schedules are
        // independent rather than shared or staggered by the first flight's completion.
        val startGap = secondFrames.first() - firstFrames.first()
        val endGap = secondFrames.last() - firstFrames.last()
        assertTrue(
            startGap in SECOND_ARCHIVE_DELAY_FRAMES..(SECOND_ARCHIVE_DELAY_FRAMES + 3),
            "the second chip starts ~100ms after the first: $startGap frames",
        )
        assertTrue(
            abs(endGap - startGap) <= 2,
            "each chip keeps its own 220ms window: start gap $startGap, end gap $endGap",
        )
        assertTrue(
            abs(secondFrames.size - firstFrames.size) <= 2,
            "neither chip waits on the other: ${firstFrames.size} vs ${secondFrames.size} frames",
        )
        assertTrue(
            (frames.firstCenters.last() - binCenter).getDistance() < 4f,
            "the first chip vanishes into the bin",
        )
        assertTrue(
            (frames.secondCenters.last() - binCenter).getDistance() < 4f,
            "the second chip vanishes into the bin",
        )
    }

    /**
     * Acceptance (story 4) bin side: two arrivals pulse the bin twice. The bin shares one scale
     * animator behind a mutex, so the second pulse waits for the first to return to rest. The test
     * counts humps in the scale signal rather than pinning either pulse to exact frames.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bothConcurrentArrivalsPulseTheBinIndependentlyAndInSequence() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(
            screenStateWithWorktrees(
                TestWorktree(LOGIN_BRANCH, LOGIN_PATH),
                TestWorktree(SECOND_BRANCH, SECOND_PATH),
            ),
        )
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val binAtRestWidth = onNodeWithContentDescription("Recycle bin (0)").captureToImage().width

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        advanceFramesUntilTagExists(CHIP_TAG)

        // Queue the second archive ~100ms into the first flight, then let both chips land.
        recordConcurrentArchiveFlight(
            firstTag = CHIP_TAG,
            secondTag = SECOND_CHIP_TAG,
            queueSecondArchive = { screenState = screenState.queuingArchive(SECOND_BRANCH, SECOND_PATH) },
        )
        onAllNodesWithTag(CHIP_TAG).assertCountEquals(0)
        onAllNodesWithTag(SECOND_CHIP_TAG).assertCountEquals(0)

        // Both arrivals are now in the bin; the first pulse may already be mid-leg. Because the
        // pulses serialize on the bin's mutex, the scale signal must show two humps separated by a
        // return to rest. The captured size grows with the icon's pulse scale.
        val pulseWidths = (1..BIN_PULSE_SAMPLE_FRAMES).map {
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("Recycle bin (2)").captureToImage().width
        }
        assertTwoSerializedBinPulses(pulseWidths, binAtRestWidth)
    }

    /**
     * Regression: undo a queued archive while its chip is in the air, then re-archive the same path
     * before it lands. Undo drops the path from the queued set but not from the in-flight chip, so
     * the re-archive must reuse that chip rather than adding a second overlay keyed by the same path.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rearchivingAPathWhoseChipIsStillFlyingKeepsOneChip() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        advanceFramesUntilTagExists(CHIP_TAG)
        repeat(SECOND_ARCHIVE_DELAY_FRAMES) { mainClock.advanceTimeByFrame() }
        onAllNodesWithTag(CHIP_TAG).assertCountEquals(1)

        screenState = screenState.undoingArchive(LOGIN_PATH)
        mainClock.advanceTimeByFrame()
        screenState = screenState.queuingArchive(LOGIN_BRANCH, LOGIN_PATH)
        // The re-archive reaches the flight effect a frame or two after the state change.
        repeat(3) { mainClock.advanceTimeByFrame() }

        // The original chip is still the only chip; without the guard the re-archive adds a second
        // chip for the same path.
        onAllNodesWithTag(CHIP_TAG).assertCountEquals(1)
        onNodeWithTag(CHIP_TAG).assertExists()

        repeat(MAX_CHIP_FLIGHT_FRAMES) { mainClock.advanceTimeByFrame() }
        onAllNodesWithTag(CHIP_TAG).assertCountEquals(0)
    }

    /**
     * Acceptance: the bin keeps its resting scale while the chip flies and pulses once the chip has
     * covered the 220ms flight, not when the archive is queued.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun binDoesNotPulseDuringFlightButPulsesOnceTheChipArrives() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val binAtRest = onNodeWithContentDescription("Recycle bin (0)").captureToImage().pixels()

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        advanceFramesUntilTagExists(CHIP_TAG)
        onNodeWithTag(CHIP_TAG).assertExists()

        // The archive is already queued, yet the bin holds its rest scale for the whole flight.
        // Samples stop once the chip is close enough to cast its shadow on the icon; from there on the
        // chip and its shadow draw over the bin, and the pulse legitimately starts on arrival.
        var flightFrames = 0
        var restingSamples = 0
        while (true) {
            val chip = onAllNodesWithTag(CHIP_TAG).fetchSemanticsNodes().firstOrNull() ?: break
            val binNode = onNodeWithContentDescription("Recycle bin (1)")
            val chipCenter = chip.boundsInRoot.center
            val binCenter = binNode.fetchSemanticsNode().boundsInRoot.center
            if ((chipCenter - binCenter).getDistance() >= CHIP_CLEAR_OF_BIN_PX) {
                assertTrue(
                    binAtRest.contentEquals(binNode.captureToImage().pixels()),
                    "bin pulses while the chip is still in flight after $flightFrames frames",
                )
                restingSamples++
            }
            mainClock.advanceTimeByFrame()
            flightFrames++
            assertTrue(flightFrames < 30, "the chip should finish within its 220ms window")
        }
        assertTrue(flightFrames in 10..20, "sampled the bin while the chip flew for $flightFrames frames")
        assertTrue(restingSamples >= 8, "sampled the bin at rest while the chip was away: $restingSamples")

        // The chip has landed, so the pulse is mid-leg 160ms later. A pulse that had started when
        // the archive was queued (the whole 220ms flight earlier) would already be back at rest.
        mainClock.advanceTimeBy(160)
        val pulsingBin = onNodeWithContentDescription("Recycle bin (1)")
        assertFalse(
            binAtRest.contentEquals(pulsingBin.captureToImage().pixels()),
            "bin pulses once the chip arrives, not while it flies",
        )
        mainClock.advanceTimeBy(240)
        assertTrue(
            binAtRest.contentEquals(pulsingBin.captureToImage().pixels()),
            "bin returns to rest after the 160ms out and back pulse",
        )
    }

    /**
     * Decision 6 fallback: with no row bounds captured there is no flight, so the bin pulses on
     * queue instead. Feedback is never lost just because the chip could not start.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun binPulsesOnQueueWhenNoSourceBoundsWereCapturedForTheFlight() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        setContent {
            EngHubScreenWithArchiveQueueing(screenState) { screenState = it }
        }
        mainClock.advanceTimeByFrame()
        val binAtRest = onNodeWithContentDescription("Recycle bin (0)").captureToImage().pixels()

        // feature/ghost has no rendered row, so the flight overlay can never anchor it.
        screenState = screenState.queuingArchive(GHOST_BRANCH, GHOST_PATH)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onAllNodesWithTag(GHOST_CHIP_TAG).assertCountEquals(0)

        assertTrue(
            (1..12).any {
                mainClock.advanceTimeBy(20)
                !binAtRest.contentEquals(
                    onNodeWithContentDescription("Recycle bin (1)").captureToImage().pixels(),
                )
            },
            "bin pulses on queue when no flight can start",
        )
        mainClock.advanceTimeBy(400)
        assertTrue(
            binAtRest.contentEquals(onNodeWithContentDescription("Recycle bin (1)").captureToImage().pixels()),
            "bin returns to rest after the fallback pulse",
        )
        onAllNodesWithTag(GHOST_CHIP_TAG).assertCountEquals(0)
    }

    /**
     * Inverting the cosine ease turns every chip sample into elapsed flight time, so the assertion
     * does not depend on which frame the animation happened to start on.
     */
    private fun assertSinusoidalFlight(
        centers: List<Offset>,
        origin: Offset,
        destination: Offset,
    ) {
        assertTrue(centers.size in 14..17, "chip should fly for one 220ms window, sampled ${centers.size} frames")
        assertTrue((centers.first() - origin).getDistance() < 2f, "chip departs from the row")
        assertTrue((centers.last() - destination).getDistance() < 4f, "chip arrives at the bin")

        val times = centers.map { center -> elapsedFlightTime(center, origin, destination) }
        times.zipWithNext { earlier, later -> assertTrue(later >= earlier, "chip never flies backwards") }
        val interiorTimes = times.filter { it in 0.05f..0.95f }
        assertTrue(interiorTimes.size >= 8, "need interior samples to compare against the sinusoid: $times")
        interiorTimes.zipWithNext { earlier, later ->
            assertEquals(FRAME_PROGRESS, later - earlier, 0.01f)
        }
    }

    private fun elapsedFlightTime(
        center: Offset,
        origin: Offset,
        destination: Offset,
    ): Float {
        val flight = destination - origin
        val distance = flight.x * flight.x + flight.y * flight.y
        val progress = ((center - origin).x * flight.x + (center - origin).y * flight.y) / distance
        return (acos(1.0 - 2.0 * progress.coerceIn(0f, 1f)) / PI).toFloat()
    }

    @Test
    fun flightProfileIsCosineEaseInOutThatShrinksTheChip() {
        // (1 - cos(pi * t)) / 2: slow departure, fast middle, slow arrival.
        assertEquals(0f, SinusoidalEaseInOut.transform(0f), 0.0001f)
        assertEquals(0.5f, SinusoidalEaseInOut.transform(0.5f), 0.0001f)
        assertEquals(1f, SinusoidalEaseInOut.transform(1f), 0.0001f)
        assertTrue(SinusoidalEaseInOut.transform(0.25f) < 0.25f, "departure is slower than linear")
        assertTrue(SinusoidalEaseInOut.transform(0.75f) > 0.75f, "arrival is slower than linear")
        assertEquals(1f, worktreeArchiveFlightScale(0f), 0.0001f)
        assertTrue(worktreeArchiveFlightScale(1f) < 0.5f, "chip is small enough to vanish into the bin")
    }
}

/** One chip's rendered bounds per animation frame, from the frame it appears to the frame it lands. */
private data class ArchiveFlightSamples(
    val centers: List<Offset>,
    val widths: List<Float>,
)

/** The frames, and last positions, of two chips whose flights overlap. */
private data class ConcurrentFlightFrames(
    val firstFrames: List<Int>,
    val secondFrames: List<Int>,
    val firstCenters: List<Offset>,
    val secondCenters: List<Offset>,
)

/**
 * Follows two chips frame by frame until both have landed, queueing the second archive partway
 * through the first flight. Recording stops once both are gone, so a late replay is never sampled.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.recordConcurrentArchiveFlight(
    firstTag: String,
    secondTag: String,
    queueSecondArchive: () -> Unit,
): ConcurrentFlightFrames {
    val firstFrames = mutableListOf<Int>()
    val secondFrames = mutableListOf<Int>()
    val firstCenters = mutableListOf<Offset>()
    val secondCenters = mutableListOf<Offset>()
    var frame = 0
    while (frame < 2 * MAX_CHIP_FLIGHT_FRAMES) {
        mainClock.advanceTimeByFrame()
        if (frame == SECOND_ARCHIVE_DELAY_FRAMES) queueSecondArchive()
        frame++
        val firstChip = onAllNodesWithTag(firstTag).fetchSemanticsNodes().firstOrNull()
        val secondChip = onAllNodesWithTag(secondTag).fetchSemanticsNodes().firstOrNull()
        firstChip?.let {
            firstFrames += frame
            firstCenters += it.boundsInRoot.center
            assertDistinctChips(it.boundsInRoot.center, secondChip?.boundsInRoot?.center, frame)
        }
        secondChip?.let {
            secondFrames += frame
            secondCenters += it.boundsInRoot.center
        }
        if (chipsHaveLanded(firstChip, secondChip, firstFrames, secondFrames)) break
    }
    return ConcurrentFlightFrames(firstFrames, secondFrames, firstCenters, secondCenters)
}

/** Two chips in the air at once are separate chips on separate rows, not one chip drawn twice. */
private fun assertDistinctChips(
    firstCenter: Offset,
    secondCenter: Offset?,
    frame: Int,
) {
    if (secondCenter == null) return
    assertTrue((firstCenter - secondCenter).getDistance() > 20f, "each archive flies its own chip at frame $frame")
}

/** Both chips have appeared and are now gone, so the concurrent window is fully sampled. */
private fun chipsHaveLanded(
    firstChip: SemanticsNode?,
    secondChip: SemanticsNode?,
    firstFrames: List<Int>,
    secondFrames: List<Int>,
): Boolean = firstChip == null && secondChip == null && firstFrames.isNotEmpty() && secondFrames.isNotEmpty()

/** Follows a chip frame by frame until it vanishes into the bin, or the flight window is exceeded. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.recordArchiveFlightChip(tag: String): ArchiveFlightSamples {
    val centers = mutableListOf<Offset>()
    val widths = mutableListOf<Float>()
    while (centers.size < MAX_CHIP_FLIGHT_FRAMES) {
        mainClock.advanceTimeByFrame()
        val chip = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: break
        centers += chip.boundsInRoot.center
        widths += chip.boundsInRoot.width
    }
    return ArchiveFlightSamples(centers = centers, widths = widths)
}

/** A queued archive starts its chip only after the flight effect runs on a following frame. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.advanceFramesUntilTagExists(tag: String) {
    repeat(MAX_CHIP_START_FRAMES) {
        if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        mainClock.advanceTimeByFrame()
    }
}

/** Screen content whose archive click queues the archive into the mutable test screen state. */
@Composable
private fun EngHubScreenWithArchiveQueueing(
    screenState: EngHubScreenState,
    onScreenStateChange: (EngHubScreenState) -> Unit,
) {
    val baseActions = worktreeScreenActions()
    MaterialTheme {
        EngHubScreenContent(
            state = screenState,
            actions = baseActions.copy(
                worktrees = baseActions.worktrees.copy(
                    worktrees = baseActions.worktrees.worktrees.copy(onArchiveWorktree = { branch, path ->
                        onScreenStateChange(screenState.queuingArchive(branch, path))
                    }),
                ),
            ),
        )
    }
}

private data class TestWorktree(
    val branch: String,
    val path: String,
)

private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

/**
 * Counts the humps in the bin's scale signal. Two serialized pulses rise, return to rest, then rise
 * again; overlapping or missing pulses merge into one hump or leave no hump at all.
 */
private fun assertTwoSerializedBinPulses(pulseWidths: List<Int>, restWidth: Int) {
    val strongest = pulseWidths.max()
    assertTrue(strongest > restWidth, "the bin never scaled up from rest width $restWidth: $pulseWidths")
    val threshold = restWidth + (strongest - restWidth) / 4
    val humps = pulseWidths.humpsAbove(threshold).filter { it.last - it.first >= MIN_BIN_PULSE_HUMP_FRAMES }
    assertEquals(2, humps.size, "expected one bin pulse per arrival, saw ${humps.size}: $pulseWidths")
    assertTrue(humps[0].last < humps[1].first, "the pulses must not overlap: ${humps[0]} then ${humps[1]}")
}

/** Contiguous runs of samples above [threshold], in sample order. */
private fun List<Int>.humpsAbove(threshold: Int): List<IntRange> {
    val humps = mutableListOf<IntRange>()
    var index = 0
    while (index < size) {
        if (this[index] <= threshold) {
            index++
            continue
        }
        val start = index
        while (index + 1 < size && this[index + 1] > threshold) index++
        humps += start..index
        index++
    }
    return humps
}

/** Mirrors the view model: a queued archive stays newly queued until it is cleared. */
private fun EngHubScreenState.queuingArchive(branch: String, worktreePath: String): EngHubScreenState {
    val entries = archiveBinEntries.filterNot { it.worktreePath == worktreePath } + WorktreeArchiveBinEntry(
        repository = "widgets",
        branch = branch,
        remainingSeconds = 60,
        worktreePath = worktreePath,
        queueId = "queued-${branch.replace('/', '-')}",
    )
    return withArchiveEntries(entries)
}

/** Undo drops the entry and its queued path, leaving any chip already in the air untouched. */
private fun EngHubScreenState.undoingArchive(worktreePath: String): EngHubScreenState {
    val entries = archiveBinEntries.filterNot { it.worktreePath == worktreePath }
    return withArchiveEntries(entries)
}

/** Keeps the queued and newly-queued path sets in step with the bin entries. */
private fun EngHubScreenState.withArchiveEntries(entries: List<WorktreeArchiveBinEntry>): EngHubScreenState {
    val queuedPaths = entries.mapTo(mutableSetOf(), WorktreeArchiveBinEntry::worktreePath)
    return copy(
        archiveBinEntries = entries,
        worktrees = worktrees.copy(
            queuedArchiveWorktreePaths = queuedPaths,
            newlyQueuedArchiveWorktreePaths = queuedPaths,
        ),
    )
}

private fun screenStateWithWorktrees(vararg worktrees: TestWorktree) = EngHubScreenState(
    selectedPane = EngHubPane.Worktrees,
    paneAvailability = EngHubPane.entries.associateWith { EngHubPaneAvailability(isEnabled = true) },
    actionError = null,
    archiveBinEntries = emptyList(),
    globalExistingBranchDiscovery = GlobalExistingBranchDiscoveryUiState(),
    pullRequests = PullRequestsPaneState(result = null, organizationIdsEmpty = false, setupStatuses = emptyMap()),
    notifications = NotificationsPaneState(result = null, actingOnThreadIds = emptySet(), setupStatuses = emptyMap()),
    worktrees = WorktreePanelState(
        localRepositories = listOf(
            LocalRepositoryUiState(
                name = "widgets",
                path = "/repos/widgets",
                isExpanded = true,
                worktrees = worktrees.map { LocalWorktreeUiState(it.branch, it.path, isDirty = false) },
            ),
        ),
        forceArchiveRequest = null,
        setupStatuses = emptyMap(),
        archivingWorktreePaths = emptySet(),
    ),
    settings = createEngHubSettingsUiState(
        engHubConfig = representativeEngHubConfig(),
        gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
        gitHubSecret = GitHubSecret(githubToken = "token"),
    ),
)

private fun worktreeScreenActions() = EngHubScreenActions(
    onPaneSelected = {},
    onClearActionError = {},
    onUndoQueuedWorktreeArchive = {},
    checkoutWorktreePath = { _, _ -> WorktreePath("/tmp/worktree") },
    onDiscoverGlobalExistingBranches = {},
    onDiscoverGlobalExistingPullRequests = {},
    onCheckoutExistingBranch = { _, _, _ -> },
    pullRequests = PullRequestPaneActions(onOpenInBrowser = {}, onCheckoutAndOpen = { _, _ -> }),
    notifications = NotificationActions(
        onOpenInBrowser = {},
        onCheckoutAndOpen = { _, _ -> },
        onApprove = {},
        onMarkDone = {},
        onUnsubscribe = {},
    ),
    worktrees = WorktreePanelActions(
        onAddRepository = {},
        onToggleRepository = {},
        onCreateWorktreeFromRepository = {},
        onRepositoryCreateWorktreeRequestHandled = {},
        onDiscoverExistingBranches = {},
        onDiscoverExistingPullRequest = { _, _ -> },
        onCheckoutExistingBranch = { _, _, _ -> },
        onConfirmUseUnrelatedExistingBranch = {},
        onDismissUseUnrelatedExistingBranchConfirmation = {},
        onAbortWorktreeConflict = {},
        onLeaveWorktreeConflictAsIs = {},
        worktrees = LocalWorktreeActions(
            onOpenWorktree = { _, _ -> },
            onOpenPullRequest = {},
            onArchiveWorktree = { _, _ -> },
            onCreateWorktree = {},
            onUpdateFromOrigin = { _, _, _ -> },
            onUpdateFromParent = { _, _, _ -> },
            onRebaseOntoParent = { _, _, _ -> },
            onMergeOntoParent = { _, _, _ -> },
        ),
        forceArchive = ForceArchiveWorktreeActions(onConfirm = {}, onDismiss = {}),
    ),
    settings = EngHubSettingsActions(),
)
