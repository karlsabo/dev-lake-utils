package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubSettingsUiStateTest {
    @Test
    fun mapsEveryLoadedSettingWithoutExposingTheToken() {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )

        assertEquals(listOf("acme", "widgets"), state.organizationIds)
        assertEquals("300", state.pollIntervalSeconds)
        assertEquals("60", state.worktreePollIntervalSeconds)
        assertEquals("/workspace", state.repositoriesBaseDir)
        assertEquals("octocat", state.gitHubAuthor)
        assertEquals("/workspace/plans", state.planningMarkdownDir)
        assertEquals("- PagerDuty: inspect the active incident", state.alertTriageWhereToLook)
        assertEquals("/bin/bash", state.setupShell)
        assertEquals("/secrets/github.json", state.gitHubTokenPath)
        assertEquals("••••••••", state.gitHubToken.maskedValue)
        assertEquals("github_pat_private", state.gitHubToken.value)
        assertEquals("GitHubTokenUiState()", state.gitHubToken.toString())
        assertEquals(
            listOf(
                SettingsLocalRepositoryUiState(
                    path = "/workspace/api",
                    setupCommands = listOf("cp .env.example .env", "direnv allow"),
                ),
                SettingsLocalRepositoryUiState(
                    path = "/workspace/web",
                    setupCommands = emptyList(),
                ),
            ),
            state.localRepositories,
        )
    }
}

internal fun representativeEngHubConfig() = EngHubConfig(
    organizationIds = listOf("acme", "widgets"),
    pollIntervalMs = 300_000,
    worktreePollIntervalMs = 60_000,
    repositoriesBaseDir = "/workspace",
    gitHubAuthor = "octocat",
    planningMarkdownDir = "/workspace/plans",
    llmTemplateValues = mapOf(
        ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to "- PagerDuty: inspect the active incident",
        "UNKNOWN_TEMPLATE" to "keep me",
    ),
    localRepositories = listOf(
        LocalRepositoryConfig(
            path = "/workspace/api",
            setupCommands = listOf("cp .env.example .env", "direnv allow"),
        ),
        LocalRepositoryConfig(path = "/workspace/web"),
    ),
    setupShell = "/bin/bash",
)
