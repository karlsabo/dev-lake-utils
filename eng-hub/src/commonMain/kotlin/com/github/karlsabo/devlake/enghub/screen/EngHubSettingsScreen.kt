package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

private val NavButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@Composable
internal fun EngHubSettingsScreen(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigation,
    modifier: Modifier = Modifier,
    actions: EngHubSettingsActions = EngHubSettingsActions(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < SETTINGS_COMPACT_WIDTH
        val navigationState = navigation.state
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsSearchField(
                query = navigationState.query,
                onQueryChange = navigation::updateQuery,
                onClear = navigation::clearQuery,
            )
            Spacer(modifier = Modifier.size(8.dp))
            SettingsSectionNavigation(
                selectedSection = navigationState.section,
                sectionsWithErrors = state.sectionsWithValidationErrors(),
                compact = compact,
                onSelect = navigation::selectSection,
            )
            state.persistenceError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(top = 8.dp).testTag("settings-persistence-error"),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            SettingsBody(
                state = state,
                navigation = navigation,
                compact = compact,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(SEARCH_SETTINGS_LABEL) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .semantics { contentDescription = CLEAR_SEARCH_LABEL }
                        .testTag("settings-search-clear"),
                ) {
                    Text("×", style = MaterialTheme.typography.h6)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("settings-search"),
    )
}

@Composable
private fun SettingsSectionNavigation(
    selectedSection: EngHubSettingsSection,
    sectionsWithErrors: Set<EngHubSettingsSection>,
    compact: Boolean,
    onSelect: (EngHubSettingsSection) -> Unit,
) {
    if (compact) {
        CompactSectionSelector(selectedSection, sectionsWithErrors, onSelect)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EngHubSettingsSection.entries.forEach { section ->
            SectionNavButton(
                section = section,
                selected = section == selectedSection,
                hasValidationError = section in sectionsWithErrors,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun SectionNavButton(
    section: EngHubSettingsSection,
    selected: Boolean,
    hasValidationError: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        contentPadding = NavButtonPadding,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
            contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
        ),
        modifier = Modifier
            .semantics {
                contentDescription = section.accessibleLabel(hasValidationError)
                this.selected = selected
            }
            .testTag("settings-section-${section.name}"),
    ) {
        Text(section.label)
        if (hasValidationError) {
            Spacer(modifier = Modifier.size(4.dp))
            Text("!", modifier = Modifier.testTag("settings-section-error-${section.name}"))
        }
    }
}

@Composable
private fun CompactSectionSelector(
    selectedSection: EngHubSettingsSection,
    sectionsWithErrors: Set<EngHubSettingsSection>,
    onSelect: (EngHubSettingsSection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .semantics {
                    contentDescription = selectedSection.accessibleLabel(selectedSection in sectionsWithErrors)
                }
                .testTag("settings-section-selector"),
        ) {
            Text(selectedSection.label)
            if (selectedSection in sectionsWithErrors) {
                Spacer(modifier = Modifier.size(4.dp))
                Text("!", modifier = Modifier.testTag("settings-section-error-${selectedSection.name}"))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EngHubSettingsSection.entries.forEach { section ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelect(section)
                    },
                    modifier = Modifier
                        .semantics { contentDescription = section.accessibleLabel(section in sectionsWithErrors) }
                        .testTag("settings-section-option-${section.name}"),
                ) {
                    Text(section.label)
                    if (section in sectionsWithErrors) {
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("!")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigation,
    compact: Boolean,
    actions: EngHubSettingsActions,
    modifier: Modifier = Modifier,
) {
    val renderPlan = settingsRenderPlan(state, navigation.state)
    val scrollState = rememberScrollState()
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(end = SettingsScrollbarGutter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (renderPlan.noMatches) {
                Text(
                    text = NO_MATCHING_SETTINGS_LABEL,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.testTag("settings-no-matches"),
                )
            }
            renderPlan.sections.forEach { sectionView ->
                SettingsSection(sectionView.section.label) {
                    SettingsSectionContent(sectionView, state, navigation, compact, actions)
                }
            }
        }
        SettingsVerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** Repositories lays out its own cards; every other section is one card around its setting groups. */
@Composable
private fun SettingsSectionContent(
    sectionView: SettingsSectionView,
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigation,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    val groups = sectionView.groups
    when (sectionView.section) {
        EngHubSettingsSection.Repositories -> RepositoriesSettingsContent(
            state = state,
            sectionView = sectionView,
            navigation = navigation,
            compact = compact,
            actions = actions,
        )

        EngHubSettingsSection.GitHubAccess -> SettingsCard(title = null) {
            GitHubAccessSettings(state = state, groups = groups, compact = compact, actions = actions)
        }

        EngHubSettingsSection.GitLabAccess -> SettingsCard(title = null) {
            GitLabAccessSettings(state = state, groups = groups, compact = compact, actions = actions)
        }

        EngHubSettingsSection.GitHubActivity -> SettingsCard(title = null) {
            GitHubActivitySettings(state = state, groups = groups, actions = actions)
        }

        EngHubSettingsSection.LlmSkillTemplates -> SettingsCard(title = null) {
            LlmSkillTemplateSettings(state = state, groups = groups, actions = actions)
        }

        EngHubSettingsSection.PlanningAndSetup -> SettingsCard(title = null) {
            PlanningAndSetupSettings(state = state, groups = groups, compact = compact, actions = actions)
        }
    }
}
