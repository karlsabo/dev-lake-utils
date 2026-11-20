package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.component.CompactButtonPadding
import com.github.karlsabo.devlake.enghub.component.SecondaryButton
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

internal const val NO_REPOSITORIES_LABEL = "No repositories configured yet"
internal const val NEW_REPOSITORY_PATH_LABEL = "New repository path"

/** Tall enough for about six repositories before the list scrolls. */
private val RepositoryListMaxHeight = 312.dp

internal data class RepositoryChooserActions(
    val onSelectRepository: (Long) -> Unit,
    val onRepositoryAdded: (Long) -> Unit,
    val onUndoRemoval: () -> Unit,
)

/** The repository list with its add-repository footer; selecting a row drives the detail pane beside it. */
@Composable
internal fun RepositoryChooserPane(
    chooser: EngHubRepositoryChooser,
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
    chooserActions: RepositoryChooserActions,
    modifier: Modifier = Modifier,
) {
    SettingsPane(modifier = modifier) {
        Column {
            if (chooser.choices.isEmpty()) {
                Box(modifier = Modifier.padding(12.dp)) {
                    SettingsEmptyHint(text = NO_REPOSITORIES_LABEL, tag = "repository-empty")
                }
            } else {
                RepositoryChoiceList(chooser = chooser, onSelect = chooserActions.onSelectRepository)
            }
            Divider(color = settingsBorderColor())
            AddRepositoryFooter(state = state, actions = actions, chooserActions = chooserActions)
        }
    }
}

@Composable
private fun RepositoryChoiceList(
    chooser: EngHubRepositoryChooser,
    onSelect: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxWidth().heightIn(max = RepositoryListMaxHeight)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(end = SettingsScrollbarGutter).testTag("repository-chooser"),
        ) {
            items(chooser.choices, key = { choice -> choice.id }) { choice ->
                RepositoryChoiceRow(
                    choice = choice,
                    selected = choice.id == chooser.selectedRepositoryId,
                    onSelect = { onSelect(choice.id) },
                )
            }
        }
        SettingsVerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .matchParentSize()
                .wrapContentWidth(Alignment.End)
                .padding(vertical = 4.dp, horizontal = 3.dp),
        )
    }
}

@Composable
private fun RepositoryChoiceRow(
    choice: EngHubRepositoryChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface)
            .selectable(selected = selected, onClick = onSelect)
            .semantics { contentDescription = choice.accessibleLabel() }
            .testTag("repository-choice-${choice.id}")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = choice.name,
                style = MaterialTheme.typography.body2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = choice.path,
                style = MaterialTheme.typography.caption,
                color = colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (choice.hasValidationError) {
            Text(
                text = "!",
                color = colors.error,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("repository-choice-${choice.id}-error"),
            )
        }
    }
}

@Composable
private fun AddRepositoryFooter(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
    chooserActions: RepositoryChooserActions,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsField(
            label = NEW_REPOSITORY_PATH_LABEL,
            value = state.localRepositoryDraft,
            tag = "repository-new",
            presentation = SettingsFieldPresentation(error = state.localRepositoryError),
            onValueChange = actions.onLocalRepositoryDraftChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Browse",
                tag = "repository-new-browse",
                onClick = actions.onChooseLocalRepositoryDraft,
            )
            Button(
                onClick = { actions.onAddLocalRepository()?.let(chooserActions.onRepositoryAdded) },
                contentPadding = CompactButtonPadding,
                modifier = Modifier.testTag("repository-add"),
            ) {
                Text("Add repository")
            }
        }
        state.removedLocalRepositoryPath?.let { path ->
            RemovalUndoRow(path = path, onUndo = chooserActions.onUndoRemoval)
        }
    }
}

@Composable
private fun RemovalUndoRow(
    path: String,
    onUndo: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("repository-removal-undo"),
    ) {
        Text(
            text = "Removed ${repositoryBasename(path)}",
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        SecondaryButton(text = "Undo", tag = "repository-removal-undo-action", onClick = onUndo)
    }
}
