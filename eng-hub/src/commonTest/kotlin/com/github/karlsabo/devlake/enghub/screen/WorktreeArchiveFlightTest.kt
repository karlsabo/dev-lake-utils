package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_FRAME_MS = 16f
private const val FRAME_PROGRESS = TEST_FRAME_MS / WORKTREE_ARCHIVE_FLIGHT_DURATION_MS
private const val ROW_TAG = "worktree-row-feature/login"
private const val CHIP_TAG = "archive-flight-chip-feature/login"
private const val SKIPPED_ROW_TAG = "worktree-row-feature/signup"
private const val SKIPPED_CHIP_TAG = "archive-flight-chip-feature/signup"
private const val LOGIN_BRANCH = "feature/login"
private const val LOGIN_PATH = "/repos/widgets-feature-login"
private const val SIGNUP_BRANCH = "feature/signup"
private const val SIGNUP_PATH = "/repos/widgets-feature-signup"
private const val MAX_CHIP_START_FRAMES = 5

class WorktreeArchiveFlightTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveMenuClickFliesChipFromRowToBinAlongSinusoidWhileRowCollapses() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(screenStateWithWorktrees(TestWorktree(LOGIN_BRANCH, LOGIN_PATH)))
        val baseActions = worktreeScreenActions()
        setContent {
            MaterialTheme {
                EngHubScreenContent(
                    state = screenState,
                    actions = baseActions.copy(
                        worktrees = baseActions.worktrees.copy(
                            worktrees = baseActions.worktrees.worktrees.copy(onArchiveWorktree = { branch, path ->
                                screenState = screenState.queuingArchive(branch, path)
                            }),
                        ),
                    ),
                )
            }
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

        val chipCenters = mutableListOf<Offset>()
        val chipWidths = mutableListOf<Float>()
        while (chipCenters.size < 30) {
            mainClock.advanceTimeByFrame()
            val chip = onAllNodesWithTag(CHIP_TAG).fetchSemanticsNodes().firstOrNull() ?: break
            chipCenters += chip.boundsInRoot.center
            chipWidths += chip.boundsInRoot.width
        }
        assertTrue(chipWidths.last() < chipWidths.first() / 2f, "chip shrinks: $chipWidths")
        onNodeWithTag(CHIP_TAG).assertDoesNotExist()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithTag(ROW_TAG).assertDoesNotExist()

        assertSinusoidalFlight(chipCenters, origin = rowCenter, destination = binCenter)
    }

    /**
     * Regression: an archive queued while a chip is already flying must skip its chip outright.
     * Marking it late replayed a delayed chip from a row that had already collapsed.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveQueuedDuringFlightSkipsItsChipInsteadOfReplayingItLate() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screenState by mutableStateOf(
            screenStateWithWorktrees(
                TestWorktree(LOGIN_BRANCH, LOGIN_PATH),
                TestWorktree(SIGNUP_BRANCH, SIGNUP_PATH),
            ),
        )
        val baseActions = worktreeScreenActions()
        setContent {
            MaterialTheme {
                EngHubScreenContent(
                    state = screenState,
                    actions = baseActions.copy(
                        worktrees = baseActions.worktrees.copy(
                            worktrees = baseActions.worktrees.worktrees.copy(onArchiveWorktree = { branch, path ->
                                screenState = screenState.queuingArchive(branch, path)
                            }),
                        ),
                    ),
                )
            }
        }
        mainClock.advanceTimeByFrame()
        val binCenter = onNodeWithContentDescription("Recycle bin (0)").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag(SKIPPED_ROW_TAG).assertExists()

        onNodeWithContentDescription("Worktree actions for feature/login").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Archive").performClick()
        advanceFramesUntilTagExists(CHIP_TAG)
        onNodeWithTag(CHIP_TAG).assertExists()

        // A second archive is queued while the first chip is still in the air.
        screenState = screenState.queuingArchive(SIGNUP_BRANCH, SIGNUP_PATH)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        // The first chip keeps flying, and the second archive never gets one of its own.
        onNodeWithTag(CHIP_TAG).assertExists()
        onNodeWithTag(SKIPPED_CHIP_TAG).assertDoesNotExist()

        val chipCenters = mutableListOf<Offset>()
        while (true) {
            mainClock.advanceTimeByFrame()
            onNodeWithTag(SKIPPED_CHIP_TAG).assertDoesNotExist()
            val chip = onAllNodesWithTag(CHIP_TAG).fetchSemanticsNodes().firstOrNull() ?: break
            chipCenters += chip.boundsInRoot.center
            assertTrue(chipCenters.size < 20, "the first chip should finish within its 220ms window")
        }
        assertTrue(
            chipCenters.size in 6..17,
            "the first chip keeps flying after the skipped archive is queued: ${chipCenters.size} frames",
        )
        assertTrue((chipCenters.last() - binCenter).getDistance() < 4f, "the first chip still lands on the bin")

        // Watch well past the flight's 220ms: the skipped archive must not replay a delayed chip.
        repeat(30) {
            mainClock.advanceTimeByFrame()
            onNodeWithTag(SKIPPED_CHIP_TAG).assertDoesNotExist()
        }
        // Both rows still collapse; only the chip was skipped.
        onNodeWithTag(ROW_TAG).assertDoesNotExist()
        onNodeWithTag(SKIPPED_ROW_TAG).assertDoesNotExist()
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

/**
 * A queued archive starts its chip only after the flight effect runs on a following frame.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.advanceFramesUntilTagExists(tag: String) {
    repeat(MAX_CHIP_START_FRAMES) {
        if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        mainClock.advanceTimeByFrame()
    }
}

private data class TestWorktree(
    val branch: String,
    val path: String,
)

/** Mirrors the view model: every queued archive keeps its newly-queued flag until it is cleared. */
private fun EngHubScreenState.queuingArchive(branch: String, worktreePath: String): EngHubScreenState {
    val entries = archiveBinEntries.filterNot { it.worktreePath == worktreePath } + WorktreeArchiveBinEntry(
        repository = "widgets",
        branch = branch,
        remainingSeconds = 60,
        worktreePath = worktreePath,
        queueId = "queued-${branch.replace('/', '-')}",
        isNewlyQueued = true,
    )
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
