package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.EngHubConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngHubLlmTemplateSettingsControllerTest {
    @Test
    fun changingAlertTriageGuidancePreservesUnknownTemplatesAfter750Milliseconds() = runTest {
        val writer = RecordingConfigWriter()
        val configState = MutableConfigState(
            EngHubConfig(
                llmTemplateValues = mapOf(
                    ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to "old guidance",
                    "UNKNOWN_TEMPLATE" to "keep me",
                ),
            ),
        )
        val viewModel = settingsViewModel(writer, configState)
        val guidance = "- Incident management: inspect the incident\n- Logs: search the alert window"

        viewModel.llmTemplateSettings.updateAlertTriageWhereToLook(guidance)

        assertEquals(guidance, viewModel.uiState.value.alertTriageWhereToLook)
        advanceTimeBy(749.milliseconds)
        runCurrent()
        assertTrue(writer.savedConfigs.isEmpty())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(
            mapOf(
                ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY to guidance,
                "UNKNOWN_TEMPLATE" to "keep me",
            ),
            writer.savedConfigs.single().llmTemplateValues,
        )
    }

    @Test
    fun navigationFlushesPendingAlertTriageGuidance() = runTest {
        val events = mutableListOf<String>()
        val writer = RecordingConfigWriter { config ->
            events += "persisted ${config.llmTemplateValues[ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY]}"
        }
        val viewModel = settingsViewModel(writer)

        viewModel.llmTemplateSettings.updateAlertTriageWhereToLook("new guidance")
        launchAfterSettingsFlush(viewModel) { events += "navigated" }.join()

        assertEquals(listOf("persisted new guidance", "navigated"), events)
    }
}
