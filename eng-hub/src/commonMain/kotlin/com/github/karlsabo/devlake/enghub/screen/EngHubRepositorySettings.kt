package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.DestructiveButton
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditor
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorActions
import com.github.karlsabo.devlake.enghub.component.SetupCommandEditorState
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

internal const val REMOVE_REPOSITORY_LABEL = "Remove repository"
internal const val GENERAL_REPOSITORY_SETTINGS_LABEL = "General"
internal const val CONFIGURED_REPOSITORIES_LABEL = "Configured repositories"
internal const val SELECT_REPOSITORY_HINT = "Select a repository to edit its path and setup commands"

private val RepositoryListWidth = 300.dp

@Composable
internal fun RepositoriesSettingsContent(
    state: EngHubSettingsUiState,
    sectionView: SettingsSectionView,
    navigation: EngHubSettingsNavigation,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val groups = sectionView.groups
        val showBaseDirectory = EngHubSettingGroupId.RepositoriesBaseDirectory in groups
        val showPollInterval = EngHubSettingGroupId.WorktreePollInterval in groups
        if (showBaseDirectory || showPollInterval) {
            SettingsCard(title = GENERAL_REPOSITORY_SETTINGS_LABEL) {
                if (showBaseDirectory) RepositoriesBaseDirectoryField(state, compact, actions)
                if (showPollInterval) WorktreePollIntervalField(state, actions)
            }
        }
        if (sectionView.repositoryChooser != null || sectionView.repositoryEditor != null) {
            SettingsCard(title = CONFIGURED_REPOSITORIES_LABEL) {
                RepositoryListAndDetail(
                    state = state,
                    sectionView = sectionView,
                    navigation = navigation,
                    compact = compact,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun RepositoriesBaseDirectoryField(
    state: EngHubSettingsUiState,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
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

@Composable
private fun WorktreePollIntervalField(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
) {
    SettingsField(
        label = WORKTREE_POLL_INTERVAL_LABEL,
        value = state.worktreePollIntervalSeconds,
        tag = "worktree-poll-interval",
        presentation = SettingsFieldPresentation(error = state.worktreePollIntervalError),
        onValueChange = actions.onWorktreePollIntervalChange,
    )
}

/** The list sits beside the selected repository's details on wide panes and above them on compact ones. */
@Composable
private fun RepositoryListAndDetail(
    state: EngHubSettingsUiState,
    sectionView: SettingsSectionView,
    navigation: EngHubSettingsNavigation,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    val chooser = sectionView.repositoryChooser
    val listPane: @Composable (Modifier) -> Unit = { modifier ->
        chooser?.let {
            RepositoryChooserPane(
                chooser = it,
                state = state,
                actions = actions,
                chooserActions = repositoryChooserActions(state, navigation, actions),
                modifier = modifier,
            )
        }
    }
    val detailPane: @Composable (Modifier) -> Unit = { modifier ->
        RepositoryDetailPane(
            editor = sectionView.repositoryEditor,
            hasChoices = chooser?.choices?.isNotEmpty() == true,
            compact = compact,
            actions = actions,
            modifier = modifier,
        )
    }
    if (compact || chooser == null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listPane(Modifier.fillMaxWidth())
            detailPane(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            listPane(Modifier.width(RepositoryListWidth))
            detailPane(Modifier.weight(1f))
        }
    }
}

private fun repositoryChooserActions(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigation,
    actions: EngHubSettingsActions,
) = RepositoryChooserActions(
    onSelectRepository = navigation::selectRepository,
    onRepositoryAdded = navigation::requestRepositorySelection,
    onUndoRemoval = {
        state.removedLocalRepositoryPath?.let(navigation::requestRepositorySelectionByPath)
        actions.onUndoLocalRepositoryRemoval()
    },
)

@Composable
private fun RepositoryDetailPane(
    editor: RepositoryEditorView?,
    hasChoices: Boolean,
    compact: Boolean,
    actions: EngHubSettingsActions,
    modifier: Modifier = Modifier,
) {
    SettingsPane(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                editor != null -> RepositoryEditor(editor = editor, compact = compact, actions = actions)
                hasChoices -> SettingsEmptyHint(text = SELECT_REPOSITORY_HINT, tag = "repository-select-hint")
                else -> SettingsEmptyHint(text = NO_REPOSITORIES_LABEL, tag = "repository-detail-empty")
            }
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
    val repository = editor.repository
    RepositoryDetailHeader(
        name = repositoryBasename(repository.path),
        path = repository.path.takeUnless { editor.showPath },
        tag = "repository-$repositoryIndex-context",
    )
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DestructiveButton(
                text = REMOVE_REPOSITORY_LABEL,
                tag = "repository-$repositoryIndex-remove",
                onClick = { actions.onRemoveLocalRepository(repositoryIndex) },
            )
        }
    }
    if (editor.showSetupCommands) {
        SettingsCard(title = SETUP_COMMANDS_LABEL) {
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

@Composable
private fun RepositoryDetailHeader(
    name: String,
    path: String?,
    tag: String,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(
            text = name,
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        path?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
