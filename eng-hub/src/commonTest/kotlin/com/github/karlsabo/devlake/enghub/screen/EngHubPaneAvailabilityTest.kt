package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.LoadedGitLabConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class EngHubPaneAvailabilityTest {
    @Test
    fun gitLabOnlyAccessEnablesThePullRequestsPane() {
        val state = createEngHubSettingsUiState(
            engHubConfig = EngHubConfig(
                gitLabInstanceUrl = "https://gitlab.internal.example",
                gitLabTokenPath = "/secrets/gitlab.json",
            ),
            gitHubConfig = GitHubConfig(tokenPath = ""),
            gitHubSecret = GitHubSecret(githubToken = ""),
            gitLabConfig = LoadedGitLabConfig(
                instanceUrl = "https://gitlab.internal.example",
                tokenPath = "/secrets/gitlab.json",
                secret = GitLabSecret("saved-token"),
            ),
        )

        val availability = engHubPaneAvailability(state)

        assertTrue(availability.getValue(EngHubPane.PullRequests).isEnabled)
    }
}
