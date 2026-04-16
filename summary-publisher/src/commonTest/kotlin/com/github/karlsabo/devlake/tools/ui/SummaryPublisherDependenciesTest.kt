package com.github.karlsabo.devlake.tools.ui

import com.github.karlsabo.devlake.tools.SummaryBuilder
import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.devlake.tools.SummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.toTerseSlackMarkup
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SummaryPublisherDependenciesTest {

    @Test
    fun loadSummaryPreviewUsesProvidedDependencies() = runBlocking {
        val config = SummaryPublisherConfig(
            summaryName = "Test Summary",
            isTerseSummaryUsed = true,
        )
        val expectedSummary = MultiProjectSummary(
            startDate = LocalDate(2026, 4, 9),
            endDate = LocalDate(2026, 4, 16),
            summaryName = config.summaryName,
            projectSummaries = emptyList(),
            pagerDutyAlerts = null,
        )
        val recordingBuilder = RecordingSummaryBuilder(expectedSummary)
        val dependencies = SummaryPublisherDependencies(
            summaryBuilder = recordingBuilder,
        )
        val state = SummaryPublisherState()

        loadConfiguration(
            state = state,
            configFilePath = com.github.karlsabo.devlake.tools.summaryPublisherConfigPath,
            loadConfig = { config },
            dependencyProvider = { providedConfig ->
                assertEquals(config, providedConfig)
                dependencies
            },
        )

        loadSummaryData(state)

        assertEquals(config, state.summaryConfig)
        assertSame(dependencies, state.dependencies)
        assertEquals(1, recordingBuilder.callCount)
        assertEquals(expectedSummary.toTerseSlackMarkup(), state.topLevelSummary)
        assertEquals(emptyList(), state.projectSummaries)
        assertEquals(false, state.isLoadingSummary)
    }

    private class RecordingSummaryBuilder(
        private val summary: MultiProjectSummary,
    ) : SummaryBuilder {
        var callCount = 0

        override suspend fun createSummary(): MultiProjectSummary {
            callCount += 1
            return summary
        }
    }
}
