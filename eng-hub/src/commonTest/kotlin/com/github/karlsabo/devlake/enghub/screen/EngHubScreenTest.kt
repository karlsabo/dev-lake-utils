package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.ForceArchiveWorktreeActions
import com.github.karlsabo.devlake.enghub.component.GlobalExistingBranchDiscoveryUiState
import com.github.karlsabo.devlake.enghub.component.LocalWorktreeActions
import com.github.karlsabo.devlake.enghub.component.NotificationActions
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBin
import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveBinEntry
import com.github.karlsabo.devlake.enghub.component.WorktreePanelActions
import com.github.karlsabo.devlake.enghub.component.WorktreePanelState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngHubScreenTest {

    @Test
    fun githubPanesBecomeAvailableOnlyAfterAccessIsCommitted() {
        val initialSettings = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = ""),
            gitHubSecret = GitHubSecret(githubToken = ""),
        )

        val initialAvailability = engHubPaneAvailability(initialSettings)
        assertTrue(!initialAvailability.getValue(EngHubPane.PullRequests).isEnabled)
        assertTrue(!initialAvailability.getValue(EngHubPane.Notifications).isEnabled)

        val committedAvailability = engHubPaneAvailability(initialSettings.copy(gitHubAccessReady = true))
        assertTrue(committedAvailability.getValue(EngHubPane.PullRequests).isEnabled)
        assertTrue(committedAvailability.getValue(EngHubPane.Notifications).isEnabled)
    }

    @Test
    fun unavailablePaneFallsBackToSettingsAfterPendingEditsAreCommitted() {
        val settings = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(gitHubAuthor = ""),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "token"),
        )

        val selectedPane = availablePaneOrSettings(EngHubPane.PullRequests, settings)

        assertEquals(EngHubPane.Settings, selectedPane)
    }

    @Test
    fun failedAuthorDraftDoesNotReplaceCommittedPaneReadiness() {
        val settings = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig().copy(gitHubAuthor = "octocat"),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "token"),
        ).copy(gitHubAuthor = "", persistenceError = "save failed")

        val availability = engHubPaneAvailability(settings)

        assertTrue(availability.getValue(EngHubPane.PullRequests).isEnabled)
    }

    @Test
    fun worktreesRemainAvailableWhenSetupCommandsHaveNoCommittedShell() {
        val config = representativeEngHubConfig().copy(
            setupShell = "",
            localRepositories = listOf(
                com.github.karlsabo.devlake.enghub.LocalRepositoryConfig(
                    path = "/workspace/api",
                    setupCommands = listOf("prepare"),
                ),
            ),
        )
        val settings = createEngHubSettingsUiState(
            engHubConfig = config,
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "token"),
        )

        val availability = engHubPaneAvailability(settings).getValue(EngHubPane.Worktrees)

        assertTrue(availability.isEnabled)
        assertTrue(availability.disabledReason == null)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun unavailablePanesRemainVisibleButDisabled() = runComposeUiTest {
        val availability = EngHubPane.entries.associateWith { pane ->
            if (pane == EngHubPane.PullRequests) {
                EngHubPaneAvailability(false, "Enter a GitHub secret path and token in Settings")
            } else {
                EngHubPaneAvailability(true)
            }
        }
        setContent {
            MaterialTheme {
                EngHubSidebar(
                    selectedPane = EngHubPane.Settings,
                    onPaneSelect = {},
                    paneAvailability = availability,
                )
            }
        }

        onNodeWithContentDescription("Pull Requests").assertIsNotEnabled()
        onNodeWithContentDescription("Settings").assertIsEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fuzzySettingsActionClosesPopupAndSelectsSettingsPane() = runComposeUiTest {
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                EngHubScreenHeader(
                    selectedPane = selectedPane,
                    onPaneSelect = { selectedPane = it },
                )
            }
        }

        onNodeWithContentDescription("Open actions").performClick()
        onNodeWithText("Search actions…").performTextInput("setings")
        onNodeWithText("Settings").assertIsDisplayed().performClick()

        onAllNodesWithText("Search actions…").assertCountEquals(0)
        onAllNodesWithText("Settings").assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun createWorktreeActionIsSearchableFromGlobalActionsMenu() = runComposeUiTest {
        var createWorktreeRequests = 0
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                EngHubScreenHeader(
                    selectedPane = selectedPane,
                    onPaneSelect = { selectedPane = it },
                    onCreateWorktree = { createWorktreeRequests += 1 },
                )
            }
        }

        onNodeWithContentDescription("Open actions").performClick()
        onNodeWithText("Search actions…").performTextInput("create")
        onNodeWithText("Create Worktree").assertIsDisplayed().performClick()

        assertEquals(1, createWorktreeRequests)
        onAllNodesWithText("Search actions…").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escapeDismissesActionPopupAndRestoresTriggerFocus() = runComposeUiTest {
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                EngHubScreenHeader(
                    selectedPane = selectedPane,
                    onPaneSelect = { selectedPane = it },
                )
            }
        }

        onNodeWithContentDescription("Open actions").performClick()
        onNodeWithText("Search actions…").performKeyInput { pressKey(Key.Escape) }

        onAllNodesWithText("Search actions…").assertCountEquals(0)
        onNodeWithContentDescription("Open actions").assertIsFocused()
        onNodeWithText("Pull Requests").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveCountdownObservesInjectedClockAsTimeAdvances() = runComposeUiTest {
        mainClock.autoAdvance = false
        var nowEpochMs = 10_000L
        val archive = WorktreeArchiveJob(
            repositoryRootPath = "/repos/widgets",
            worktreePath = "/repos/widgets-feature-login",
            branch = "feature/login",
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = 10_000,
            stateUpdatedAtEpochMs = 10_000,
            deadlineAtEpochMs = 70_000,
        )
        setContent {
            val entries = collectArchiveBinEntries(listOf(archive)) { nowEpochMs }
            Text("${entries.single().remainingSeconds} seconds remaining")
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("60 seconds remaining").assertIsDisplayed()

        nowEpochMs = 11_000
        mainClock.advanceTimeBy(1_000)

        onNodeWithText("59 seconds remaining").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveBinEntryRetainsQueuedWorktreePathForUndo() = runComposeUiTest {
        val archive = WorktreeArchiveJob(
            repositoryRootPath = "/repos/widgets",
            worktreePath = "/repos/widgets-feature-login",
            branch = "feature/login",
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = 10_000,
            stateUpdatedAtEpochMs = 10_000,
            deadlineAtEpochMs = 70_000,
        )
        val undoRequests = mutableListOf<String>()
        setContent {
            WorktreeArchiveBin(
                entries = collectArchiveBinEntries(listOf(archive)) { 10_000L },
                onUndo = undoRequests::add,
            )
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Undo").performClick()

        assertEquals(listOf("/repos/widgets-feature-login"), undoRequests)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archiveCountdownUsesCurrentTimeWhenFirstArchiveIsQueued() = runComposeUiTest {
        mainClock.autoAdvance = false
        var nowEpochMs = 10_000L
        val queuedArchives = mutableStateOf(emptyList<WorktreeArchiveJob>())
        val archive = WorktreeArchiveJob(
            repositoryRootPath = "/repos/widgets",
            worktreePath = "/repos/widgets-feature-login",
            branch = "feature/login",
            state = WorktreeArchiveLifecycleState.QUEUED,
            queuedAtEpochMs = 20_000,
            stateUpdatedAtEpochMs = 20_000,
            deadlineAtEpochMs = 80_000,
        )
        setContent {
            val entries = collectArchiveBinEntries(queuedArchives.value) { nowEpochMs }
            Text(entries.singleOrNull()?.let { "${it.remainingSeconds} seconds remaining" } ?: "No archives")
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("No archives").assertIsDisplayed()

        nowEpochMs = 20_000
        queuedArchives.value = listOf(archive)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        onNodeWithText("60 seconds remaining").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recycleBinIsPlacedAboveSettings() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(width = 240.dp, height = 300.dp)) {
                    EngHubSidebar(
                        selectedPane = EngHubPane.Worktrees,
                        onPaneSelect = {},
                        archiveBinEntries = listOf(
                            WorktreeArchiveBinEntry(
                                repository = "widgets",
                                branch = "feature/login",
                                remainingSeconds = 60,
                                worktreePath = "/repos/widgets-feature-login",
                            ),
                        ),
                    )
                }
            }
        }

        val recycleBin = onNodeWithContentDescription("Recycle bin (1)").fetchSemanticsNode().boundsInRoot
        val settings = onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(recycleBin.bottom <= settings.top)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun screenRoutesArchiveUndoToItsAction() = runComposeUiTest {
        val undoRequests = mutableListOf<String>()
        setContent {
            MaterialTheme {
                EngHubScreenContent(
                    state = screenStateWithQueuedArchive(),
                    actions = screenActions(onUndoQueuedWorktreeArchive = undoRequests::add),
                )
            }
        }

        onNodeWithContentDescription("Recycle bin (1)").performClick()
        onNodeWithText("Undo").performClick()

        assertEquals(listOf("/repos/widgets-feature-login"), undoRequests)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bottomGearSelectsSettingsPane() = runComposeUiTest {
        setContent {
            var selectedPane by remember { mutableStateOf(EngHubPane.PullRequests) }
            MaterialTheme {
                Box(modifier = Modifier.size(width = 240.dp, height = 300.dp)) {
                    EngHubSidebar(
                        selectedPane = selectedPane,
                        onPaneSelect = { selectedPane = it },
                    )
                    Text(
                        text = "Selected: ${selectedPane.label}",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        val notifications = onNodeWithContentDescription("Notifications").fetchSemanticsNode().boundsInRoot
        val worktrees = onNodeWithContentDescription("Worktrees").fetchSemanticsNode().boundsInRoot
        val settings = onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.top - worktrees.bottom > worktrees.top - notifications.bottom)

        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Selected: Settings").assertIsDisplayed()
    }
}

private fun screenStateWithQueuedArchive() = EngHubScreenState(
    selectedPane = EngHubPane.PullRequests,
    paneAvailability = EngHubPane.entries.associateWith { EngHubPaneAvailability(isEnabled = true) },
    actionError = null,
    archiveBinEntries = listOf(
        WorktreeArchiveBinEntry(
            repository = "widgets",
            branch = "feature/login",
            remainingSeconds = 42,
            worktreePath = "/repos/widgets-feature-login",
        ),
    ),
    globalExistingBranchDiscovery = GlobalExistingBranchDiscoveryUiState(),
    pullRequests = PullRequestsPaneState(result = null, organizationIdsEmpty = false, setupStatuses = emptyMap()),
    notifications = NotificationsPaneState(result = null, actingOnThreadIds = emptySet(), setupStatuses = emptyMap()),
    worktrees = WorktreePanelState(
        localRepositories = emptyList(),
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

private fun screenActions(onUndoQueuedWorktreeArchive: (String) -> Unit) = EngHubScreenActions(
    onPaneSelected = {},
    onClearActionError = {},
    onUndoQueuedWorktreeArchive = onUndoQueuedWorktreeArchive,
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
        forceArchive = ForceArchiveWorktreeActions(onConfirm = { _, _ -> }, onDismiss = {}),
    ),
    settings = EngHubSettingsActions(),
)
