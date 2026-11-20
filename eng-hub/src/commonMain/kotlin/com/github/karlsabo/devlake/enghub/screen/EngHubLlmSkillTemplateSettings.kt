package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

@Composable
internal fun LlmSkillTemplateSettings(
    state: EngHubSettingsUiState,
    groups: List<EngHubSettingGroupId>,
    actions: EngHubSettingsActions,
) {
    groups.forEach { group ->
        when (group) {
            EngHubSettingGroupId.AlertTriageWhereToLook -> SettingsField(
                label = ALERT_TRIAGE_WHERE_TO_LOOK_LABEL,
                value = state.alertTriageWhereToLook,
                tag = "alert-triage-where-to-look",
                presentation = SettingsFieldPresentation(singleLine = false),
                onValueChange = actions.onAlertTriageWhereToLookChange,
            )

            else -> Unit
        }
    }
}
