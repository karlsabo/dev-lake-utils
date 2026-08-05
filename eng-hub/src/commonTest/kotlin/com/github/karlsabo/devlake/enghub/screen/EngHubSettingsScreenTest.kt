package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.representativeEngHubConfig
import com.github.karlsabo.devlake.enghub.viewmodel.POLL_INTERVAL_ERROR
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class EngHubSettingsScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysAllLoadedSettingsAndNeverAddsTheTokenToTextSemantics() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        assertField("github-token-path", "/secrets/github.json")
        assertField("github-token", "••••••••")
        assertField("organization-0", "acme")
        assertField("organization-1", "widgets")
        assertField("github-author", "octocat")
        assertField("poll-interval", "300")
        assertField("repositories-base-dir", "/workspace")
        assertField("worktree-poll-interval", "60")
        assertField("repository-0-path", "/workspace/api")
        assertField("repository-0-command-0", "cp .env.example .env")
        assertField("repository-0-command-1", "direnv allow")
        assertField("repository-1-path", "/workspace/web")
        assertField("planning-markdown-dir", "/workspace/plans")
        assertField("setup-shell", "/bin/bash")
        onAllNodesWithText("github_pat_private", substring = true).assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsGitHubAuthorWithoutASaveAction() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedAuthor: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    onGitHubAuthorChange = { editedAuthor = it },
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("github-author").performScrollTo().performTextReplacement("hubot")

        assertEquals("hubot", editedAuthor)
        onAllNodesWithText("Save").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displaysAnActionablePollingIntervalError() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        ).copy(
            pollIntervalSeconds = "0",
            pollIntervalError = POLL_INTERVAL_ERROR,
        )
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(state = state, modifier = Modifier.size(800.dp, 600.dp))
            }
        }

        onNodeWithTag("poll-interval").performScrollTo().assertTextContains("0")
        onNodeWithTag("poll-interval-error").assertTextContains(POLL_INTERVAL_ERROR)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editsPollingInterval() = runComposeUiTest {
        val state = createEngHubSettingsUiState(
            engHubConfig = representativeEngHubConfig(),
            gitHubConfig = GitHubConfig(tokenPath = "/secrets/github.json"),
            gitHubSecret = GitHubSecret(githubToken = "github_pat_private"),
        )
        var editedPollInterval: String? = null
        setContent {
            MaterialTheme {
                EngHubSettingsScreen(
                    state = state,
                    onPollIntervalChange = { editedPollInterval = it },
                    modifier = Modifier.size(800.dp, 600.dp),
                )
            }
        }

        onNodeWithTag("poll-interval").performScrollTo().performTextReplacement("301")

        assertEquals("301", editedPollInterval)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.assertField(tag: String, value: String) {
        onNodeWithTag(tag).performScrollTo().assertTextContains(value)
    }
}
