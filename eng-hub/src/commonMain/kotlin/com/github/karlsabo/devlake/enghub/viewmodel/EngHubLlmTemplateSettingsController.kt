package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

internal class EngHubLlmTemplateSettingsController(
    private val coroutineScope: CoroutineScope,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val configPersistence: EngHubConfigSettingsPersistence,
) {
    private var alertTriageCommitJob: Job? = null
    private var pendingAlertTriageWhereToLook: String? = null

    fun updateAlertTriageWhereToLook(guidance: String) {
        mutableUiState.value = mutableUiState.value.copy(alertTriageWhereToLook = guidance)
        alertTriageCommitJob?.cancel()
        pendingAlertTriageWhereToLook = guidance
        alertTriageCommitJob = coroutineScope.scheduleSettingsCommit {
            commitAlertTriageWhereToLook(guidance)
        }
    }

    suspend fun flushPendingEdits() {
        alertTriageCommitJob.cancelAndClear {
            pendingAlertTriageWhereToLook?.let { commitAlertTriageWhereToLook(it) }
        }
    }

    private suspend fun commitAlertTriageWhereToLook(guidance: String) {
        val committed = configPersistence.update(ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY) { currentConfig ->
            currentConfig.copy(
                llmTemplateValues = currentConfig.llmTemplateValues +
                    (ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to guidance),
            )
        }
        if (committed && pendingAlertTriageWhereToLook == guidance) pendingAlertTriageWhereToLook = null
    }
}
