package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

@Composable
internal fun EngHubSettingsScreen(
    state: EngHubSettingsUiState,
    onGitHubAuthorChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSection("GitHub access") {
            SettingsField("Secret file path", state.gitHubTokenPath, "github-token-path")
            SettingsField(
                label = "GitHub token",
                value = state.gitHubToken.maskedValue,
                tag = "github-token",
                password = true,
            )
        }
        SettingsSection("GitHub activity") {
            state.organizationIds.forEachIndexed { index, organizationId ->
                SettingsField("Organization ID ${index + 1}", organizationId, "organization-$index")
            }
            SettingsField(
                label = "GitHub author",
                value = state.gitHubAuthor,
                tag = "github-author",
                onValueChange = onGitHubAuthorChange,
            )
            SettingsField("Polling interval (seconds)", state.pollIntervalSeconds, "poll-interval")
        }
        SettingsSection("Repositories") {
            SettingsField("Repositories base directory", state.repositoriesBaseDir, "repositories-base-dir")
            SettingsField(
                "Worktree polling interval (seconds)",
                state.worktreePollIntervalSeconds,
                "worktree-poll-interval",
            )
            state.localRepositories.forEachIndexed { repositoryIndex, repository ->
                Text("Local repository ${repositoryIndex + 1}", style = MaterialTheme.typography.subtitle1)
                SettingsField(
                    "Repository path",
                    repository.path,
                    "repository-$repositoryIndex-path",
                )
                repository.setupCommands.forEachIndexed { commandIndex, command ->
                    SettingsField(
                        "Setup command ${commandIndex + 1}",
                        command,
                        "repository-$repositoryIndex-command-$commandIndex",
                    )
                }
            }
        }
        SettingsSection("Planning and setup") {
            SettingsField("Planning markdown directory", state.planningMarkdownDir, "planning-markdown-dir")
            SettingsField("Setup shell", state.setupShell, "setup-shell")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.h6)
        content()
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    tag: String,
    password: Boolean = false,
    onValueChange: ((String) -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange ?: {},
            label = { Text(label) },
            readOnly = onValueChange == null,
            singleLine = true,
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
