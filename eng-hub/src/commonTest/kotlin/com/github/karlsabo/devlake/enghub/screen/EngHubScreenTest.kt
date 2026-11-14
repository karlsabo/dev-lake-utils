package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
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
}
