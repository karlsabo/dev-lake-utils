package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditor
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorActions
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorState
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState

internal const val NO_REPOSITORIES_LABEL = "No repositories configured yet"
internal const val NEW_REPOSITORY_PATH_LABEL = "New repository path"
internal const val REMOVE_REPOSITORY_LABEL = "Remove repository"

@Composable
internal fun RepositoriesSettingsContent(
    state: EngHubSettingsUiState,
    sectionView: SettingsSectionView,
    navigation: EngHubSettingsNavigation,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val groups = sectionView.groups
        if (EngHubSettingGroupId.RepositoriesBaseDirectory in groups) {
            SettingsPickerField(
                label = REPOSITORIES_BASE_DIR_LABEL,
                value = state.repositoriesBaseDir,
                tag = "repositories-base-dir",
                presentation = SettingsPickerPresentation(compact = compact),
                actions = SettingsPickerActions(
                    onValueChange = actions.onRepositoriesBaseDirChange,
                    onBrowse = actions.onChooseRepositoriesBaseDir,
                ),
            )
        }
        if (EngHubSettingGroupId.WorktreePollInterval in groups) {
            SettingsField(
                label = WORKTREE_POLL_INTERVAL_LABEL,
                value = state.worktreePollIntervalSeconds,
                tag = "worktree-poll-interval",
                presentation = SettingsFieldPresentation(error = state.worktreePollIntervalError),
                onValueChange = actions.onWorktreePollIntervalChange,
            )
        }
        sectionView.repositoryChooser?.let { chooser ->
            RepositoryChooser(
                chooser = chooser,
                state = state,
                actions = actions,
                chooserActions = RepositoryChooserActions(
                    onSelectRepository = navigation::selectRepository,
                    onRepositoryAdded = navigation::requestRepositorySelection,
                    onUndoRemoval = {
                        state.removedLocalRepositoryPath?.let(navigation::requestRepositorySelectionByPath)
                        actions.onUndoLocalRepositoryRemoval()
                    },
                ),
            )
        }
        sectionView.repositoryEditor?.let { editor ->
            RepositoryEditor(editor = editor, compact = compact, actions = actions)
        }
    }
}

private data class RepositoryChooserActions(
    val onSelectRepository: (Long) -> Unit,
    val onRepositoryAdded: (Long) -> Unit,
    val onUndoRemoval: () -> Unit,
)

@Composable
private fun RepositoryChooser(
    chooser: EngHubRepositoryChooser,
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
    chooserActions: RepositoryChooserActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chooser.choices.isEmpty()) {
            SettingsEmptyHint(text = NO_REPOSITORIES_LABEL, tag = "repository-empty")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).testTag("repository-chooser"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(chooser.choices, key = { choice -> choice.id }) { choice ->
                    RepositoryChoiceRow(
                        choice = choice,
                        selected = choice.id == chooser.selectedRepositoryId,
                        onSelect = { chooserActions.onSelectRepository(choice.id) },
                    )
                }
            }
        }
        SettingsField(
            label = NEW_REPOSITORY_PATH_LABEL,
            value = state.localRepositoryDraft,
            tag = "repository-new",
            presentation = SettingsFieldPresentation(error = state.localRepositoryError),
            onValueChange = actions.onLocalRepositoryDraftChange,
        )
        Button(
            onClick = {
                actions.onAddLocalRepository()?.let(chooserActions.onRepositoryAdded)
            },
            modifier = Modifier.testTag("repository-add"),
        ) {
            Text("Add repository")
        }
        state.removedLocalRepositoryPath?.let { path ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("repository-removal-undo"),
            ) {
                Text("Removed $path")
                Button(
                    onClick = chooserActions.onUndoRemoval,
                    modifier = Modifier.testTag("repository-removal-undo-action"),
                ) {
                    Text("Undo")
                }
            }
        }
    }
}

@Composable
private fun RepositoryChoiceRow(
    choice: EngHubRepositoryChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .semantics { contentDescription = choice.accessibleLabel() }
            .testTag("repository-choice-${choice.id}")
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = choice.name,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (choice.hasValidationError) {
                    Text(
                        text = "!",
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.testTag("repository-choice-${choice.id}-error"),
                    )
                }
            }
            Text(
                text = choice.path,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RepositoryEditor(
    editor: RepositoryEditorView,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    val repositoryIndex = editor.repositoryIndex
    val repository: SettingsLocalRepositoryUiState = editor.repository
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editor.showPath) {
            SettingsPickerField(
                label = REPOSITORY_PATH_LABEL,
                value = repository.path,
                tag = "repository-$repositoryIndex-path",
                presentation = SettingsPickerPresentation(error = repository.pathError, compact = compact),
                actions = SettingsPickerActions(
                    onValueChange = { path -> actions.onLocalRepositoryPathChange(repositoryIndex, path) },
                    onBrowse = { actions.onChooseLocalRepositoryPath(repositoryIndex) },
                ),
            )
            Button(
                onClick = { actions.onRemoveLocalRepository(repositoryIndex) },
                modifier = Modifier.testTag("repository-$repositoryIndex-remove"),
            ) {
                Text(REMOVE_REPOSITORY_LABEL)
            }
        }
        if (editor.showSetupCommands) {
            if (!editor.showPath) {
                SettingsEmptyHint(text = repository.path, tag = "repository-$repositoryIndex-context")
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
}
