package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditor
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorActions
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorState
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.MASKED_TOKEN
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState

@Composable
internal fun EngHubSettingsScreen(
    state: EngHubSettingsUiState,
    modifier: Modifier = Modifier,
    actions: EngHubSettingsActions = EngHubSettingsActions(),
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.persistenceError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.testTag("settings-persistence-error"),
            )
        }
        SettingsSection("GitHub access") {
            PickerSettingsField(
                state = PickerSettingsFieldState(
                    label = "Secret file path",
                    value = state.gitHubTokenPath,
                    tag = "github-token-path",
                    error = state.gitHubTokenPathError,
                ),
                actions = PickerSettingsFieldActions(
                    onValueChange = actions.onGitHubTokenPathChange,
                    onBrowse = actions.onChooseGitHubTokenPath,
                ),
            )
            SettingsField(
                label = "GitHub token",
                value = state.gitHubToken.fieldValue,
                tag = "github-token",
                presentation = SettingsFieldPresentation(password = true, error = state.gitHubTokenError),
                onValueChange = actions.onGitHubTokenChange,
            )
        }
        GitHubActivitySettings(state = state, actions = actions)
        RepositoriesSettings(state = state, actions = actions)
        SettingsSection("Planning and setup") {
            DirectorySettingsField(
                label = "Planning markdown directory",
                value = state.planningMarkdownDir,
                tag = "planning-markdown-dir",
                onValueChange = actions.onPlanningMarkdownDirChange,
                onBrowse = actions.onChoosePlanningMarkdownDir,
            )
            SettingsField(
                label = "Setup shell",
                value = state.setupShell,
                tag = "setup-shell",
                onValueChange = actions.onSetupShellChange,
            )
        }
    }
}

@Composable
private fun RepositoriesSettings(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
) {
    SettingsSection("Repositories") {
        DirectorySettingsField(
            label = "Repositories base directory",
            value = state.repositoriesBaseDir,
            tag = "repositories-base-dir",
            onValueChange = actions.onRepositoriesBaseDirChange,
            onBrowse = actions.onChooseRepositoriesBaseDir,
        )
        SettingsField(
            label = "Worktree polling interval (seconds)",
            value = state.worktreePollIntervalSeconds,
            tag = "worktree-poll-interval",
            presentation = SettingsFieldPresentation(error = state.worktreePollIntervalError),
            onValueChange = actions.onWorktreePollIntervalChange,
        )
        state.localRepositories.forEachIndexed { repositoryIndex, repository ->
            LocalRepositorySettings(repositoryIndex, repository, actions)
        }
        state.removedLocalRepositoryPath?.let { removedPath ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("repository-removal-undo"),
            ) {
                Text("Removed $removedPath")
                Button(
                    onClick = actions.onUndoLocalRepositoryRemoval,
                    modifier = Modifier.testTag("repository-removal-undo-action"),
                ) {
                    Text("Undo")
                }
            }
        }
        SettingsField(
            label = "New repository path",
            value = state.localRepositoryDraft,
            tag = "repository-new",
            presentation = SettingsFieldPresentation(error = state.localRepositoryError),
            onValueChange = actions.onLocalRepositoryDraftChange,
        )
        Button(
            onClick = actions.onAddLocalRepository,
            modifier = Modifier.testTag("repository-add"),
        ) {
            Text("Add repository")
        }
    }
}

@Composable
private fun LocalRepositorySettings(
    repositoryIndex: Int,
    repository: SettingsLocalRepositoryUiState,
    actions: EngHubSettingsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local repository ${repositoryIndex + 1}", style = MaterialTheme.typography.subtitle1)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsField(
                    label = "Repository path",
                    value = repository.path,
                    tag = "repository-$repositoryIndex-path",
                    presentation = SettingsFieldPresentation(error = repository.pathError),
                    onValueChange = { path -> actions.onLocalRepositoryPathChange(repositoryIndex, path) },
                )
            }
            Button(
                onClick = { actions.onChooseLocalRepositoryPath(repositoryIndex) },
                modifier = Modifier.testTag("repository-$repositoryIndex-path-browse"),
            ) {
                Text("Browse")
            }
            Button(
                onClick = { actions.onRemoveLocalRepository(repositoryIndex) },
                modifier = Modifier.testTag("repository-$repositoryIndex-remove"),
            ) {
                Text("Remove")
            }
        }
        SetupCommandEditor(
            state = SetupCommandEditorState(
                repositoryIndex = repositoryIndex,
                commands = repository.setupCommands,
                draft = repository.setupCommandDraft,
                error = repository.setupCommandError,
                commandEditErrors = repository.setupCommandEditErrors,
            ),
            actions = SetupCommandEditorActions(
                onDraftChange = { command -> actions.onSetupCommandDraftChange(repositoryIndex, command) },
                onAddAt = { commandIndex -> actions.onAddSetupCommand(repositoryIndex, commandIndex) },
                onCommandChange = { commandIndex, command ->
                    actions.onSetupCommandChange(repositoryIndex, commandIndex, command)
                },
                onRemove = { commandIndex -> actions.onRemoveSetupCommand(repositoryIndex, commandIndex) },
            ),
        )
    }
}

@Composable
private fun GitHubActivitySettings(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
) {
    SettingsSection("GitHub activity") {
        state.organizationIds.forEachIndexed { index, organizationId ->
            OrganizationIdRow(
                index = index,
                organizationId = organizationId,
                onRemove = actions.onRemoveOrganizationId,
            )
        }
        SettingsField(
            label = "New organization ID",
            value = state.organizationIdDraft,
            tag = "organization-new",
            presentation = SettingsFieldPresentation(error = state.organizationIdError),
            onValueChange = actions.onOrganizationIdDraftChange,
        )
        Button(
            onClick = actions.onAddOrganizationId,
            modifier = Modifier.testTag("organization-add"),
        ) {
            Text("Add organization")
        }
        SettingsField(
            label = "GitHub author",
            value = state.gitHubAuthor,
            tag = "github-author",
            onValueChange = actions.onGitHubAuthorChange,
        )
        SettingsField(
            label = "Polling interval (seconds)",
            value = state.pollIntervalSeconds,
            tag = "poll-interval",
            presentation = SettingsFieldPresentation(error = state.pollIntervalError),
            onValueChange = actions.onPollIntervalChange,
        )
    }
}

@Composable
private fun OrganizationIdRow(
    index: Int,
    organizationId: String,
    onRemove: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsField(
                label = "Organization ID ${index + 1}",
                value = organizationId,
                tag = "organization-$index",
            )
        }
        Button(
            onClick = { onRemove(index) },
            modifier = Modifier.testTag("organization-$index-remove"),
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun DirectorySettingsField(
    label: String,
    value: String,
    tag: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    PickerSettingsField(
        state = PickerSettingsFieldState(label, value, tag),
        actions = PickerSettingsFieldActions(onValueChange, onBrowse),
    )
}

private data class PickerSettingsFieldState(
    val label: String,
    val value: String,
    val tag: String,
    val error: String? = null,
)

private data class PickerSettingsFieldActions(
    val onValueChange: (String) -> Unit,
    val onBrowse: () -> Unit,
)

@Composable
private fun PickerSettingsField(
    state: PickerSettingsFieldState,
    actions: PickerSettingsFieldActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SettingsField(
                label = state.label,
                value = state.value,
                tag = state.tag,
                presentation = SettingsFieldPresentation(error = state.error),
                onValueChange = actions.onValueChange,
            )
        }
        Button(
            onClick = actions.onBrowse,
            modifier = Modifier.testTag("${state.tag}-browse"),
        ) {
            Text("Browse")
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

private data class SettingsFieldPresentation(
    val password: Boolean = false,
    val error: String? = null,
)

@Composable
private fun SettingsField(
    label: String,
    value: String,
    tag: String,
    presentation: SettingsFieldPresentation = SettingsFieldPresentation(),
    onValueChange: ((String) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange ?: {},
            label = { Text(label) },
            readOnly = onValueChange == null,
            isError = presentation.error != null,
            singleLine = true,
            visualTransformation = if (presentation.password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth().testTag(tag).secureTextSemantics(value, presentation.password),
        )
        presentation.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(start = 16.dp).testTag("$tag-error"),
            )
        }
    }
}

private fun Modifier.secureTextSemantics(value: String, password: Boolean): Modifier {
    if (!password) return this
    val maskedText = AnnotatedString(if (value.isEmpty()) "" else MASKED_TOKEN)
    return semantics {
        inputText = maskedText
        editableText = maskedText
        isSensitiveData = true
    }
}
