package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.h6)
        content()
    }
}

@Composable
internal fun GitHubAccessSettings(
    state: EngHubSettingsUiState,
    groups: List<EngHubSettingGroupId>,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    groups.forEach { group ->
        when (group) {
            EngHubSettingGroupId.SecretFilePath -> SettingsPickerField(
                label = SECRET_FILE_PATH_LABEL,
                value = state.gitHubTokenPath,
                tag = "github-token-path",
                presentation = SettingsPickerPresentation(error = state.gitHubTokenPathError, compact = compact),
                actions = SettingsPickerActions(
                    onValueChange = actions.onGitHubTokenPathChange,
                    onBrowse = actions.onChooseGitHubTokenPath,
                ),
            )

            EngHubSettingGroupId.GitHubToken -> SettingsField(
                label = GITHUB_TOKEN_LABEL,
                value = state.gitHubToken.fieldValue,
                tag = "github-token",
                presentation = SettingsFieldPresentation(password = true, error = state.gitHubTokenError),
                onValueChange = actions.onGitHubTokenChange,
            )

            else -> Unit
        }
    }
}

@Composable
internal fun GitHubActivitySettings(
    state: EngHubSettingsUiState,
    groups: List<EngHubSettingGroupId>,
    actions: EngHubSettingsActions,
) {
    groups.forEach { group ->
        when (group) {
            EngHubSettingGroupId.OrganizationIds -> OrganizationIdsSettings(state, actions)

            EngHubSettingGroupId.GitHubAuthor -> SettingsField(
                label = GITHUB_AUTHOR_LABEL,
                value = state.gitHubAuthor,
                tag = "github-author",
                onValueChange = actions.onGitHubAuthorChange,
            )

            EngHubSettingGroupId.ActivityPollInterval -> SettingsField(
                label = ACTIVITY_POLL_INTERVAL_LABEL,
                value = state.pollIntervalSeconds,
                tag = "poll-interval",
                presentation = SettingsFieldPresentation(error = state.pollIntervalError),
                onValueChange = actions.onPollIntervalChange,
            )

            else -> Unit
        }
    }
}

@Composable
private fun OrganizationIdsSettings(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
) {
    state.organizationIds.forEachIndexed { index, organizationId ->
        OrganizationIdRow(
            index = index,
            organizationId = organizationId,
            onRemove = actions.onRemoveOrganizationId,
        )
    }
    SettingsField(
        label = NEW_ORGANIZATION_ID_LABEL,
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
            SettingsTextField(
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
internal fun PlanningAndSetupSettings(
    state: EngHubSettingsUiState,
    groups: List<EngHubSettingGroupId>,
    compact: Boolean,
    actions: EngHubSettingsActions,
) {
    groups.forEach { group ->
        when (group) {
            EngHubSettingGroupId.PlanningMarkdownDirectory -> SettingsPickerField(
                label = PLANNING_MARKDOWN_DIR_LABEL,
                value = state.planningMarkdownDir,
                tag = "planning-markdown-dir",
                presentation = SettingsPickerPresentation(compact = compact),
                actions = SettingsPickerActions(
                    onValueChange = actions.onPlanningMarkdownDirChange,
                    onBrowse = actions.onChoosePlanningMarkdownDir,
                ),
            )

            EngHubSettingGroupId.SetupShell -> SettingsField(
                label = SETUP_SHELL_LABEL,
                value = state.setupShell,
                tag = "setup-shell",
                onValueChange = actions.onSetupShellChange,
            )

            else -> Unit
        }
    }
}

@Composable
internal fun SettingsEmptyHint(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        style = MaterialTheme.typography.body2,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
