package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

@Composable
internal fun LlmSkillTemplateSettings(
    state: EngHubSettingsUiState,
    actions: EngHubSettingsActions,
) {
    SettingsSection("LLM skill templates") {
        SettingsField(
            label = "Alert triage: Where to look",
            value = state.alertTriageWhereToLook,
            tag = "alert-triage-where-to-look",
            presentation = SettingsFieldPresentation(singleLine = false),
            onValueChange = actions.onAlertTriageWhereToLookChange,
        )
    }
}
