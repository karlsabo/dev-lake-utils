package com.github.karlsabo.devlake.tools.ui

import com.github.karlsabo.devlake.tools.ProjectSummaryHolder
import com.github.karlsabo.devlake.tools.SummaryBuilder
import com.github.karlsabo.devlake.tools.SummaryMessagePublisher
import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.devlake.tools.SummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.tools.model.ProjectSummary
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
            summaryPublisher = NoOpSummaryPublisher,
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

    @Test
    fun publishSummaryUsesInjectedPublisher() = runBlocking {
        val recordingPublisher = RecordingSummaryPublisher()
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = RecordingSummaryBuilder(emptySummary()),
                summaryPublisher = recordingPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message"
                ),
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-2"),
                    message = "Project two message"
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message", "Project two message"),
                )
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Message sent!", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
    }

    @Test
    fun publishSummaryReportsFailureWhenPublisherReturnsFalse() = runBlocking {
        val recordingPublisher = RecordingSummaryPublisher(results = listOf(false))
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = RecordingSummaryBuilder(emptySummary()),
                summaryPublisher = recordingPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message"
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message"),
                )
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
    }

    @Test
    fun publishSummaryReportsFailureWhenPublisherThrows() = runBlocking {
        val recordingPublisher = ThrowingSummaryPublisher()
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = RecordingSummaryBuilder(emptySummary()),
                summaryPublisher = recordingPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message"
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message"),
                )
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
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

    private class RecordingSummaryPublisher : SummaryMessagePublisher {
        constructor() : this(emptyList())

        constructor(results: List<Boolean>) {
            remainingResults.addAll(results)
        }

        val publishedSummaries = mutableListOf<ZapierProjectSummary>()
        private val remainingResults = ArrayDeque<Boolean>()

        override suspend fun publishSummary(summary: ZapierProjectSummary): Boolean {
            publishedSummaries += summary
            return if (remainingResults.isEmpty()) {
                true
            } else {
                remainingResults.removeFirst()
            }
        }
    }

    private class ThrowingSummaryPublisher : SummaryMessagePublisher {
        val publishedSummaries = mutableListOf<ZapierProjectSummary>()

        override suspend fun publishSummary(summary: ZapierProjectSummary): Boolean {
            publishedSummaries += summary
            throw IllegalStateException("boom")
        }
    }

    private companion object {
        val NoOpSummaryPublisher = SummaryMessagePublisher { true }

        fun emptySummary() = MultiProjectSummary(
            startDate = LocalDate(2026, 4, 9),
            endDate = LocalDate(2026, 4, 16),
            summaryName = "Test Summary",
            projectSummaries = emptyList(),
            pagerDutyAlerts = null,
        )

        fun emptyProjectSummary(projectId: String) = ProjectSummary(
            project = Project(
                id = projectId.removePrefix("project-").toLongOrNull() ?: 0L,
                title = projectId,
            ),
            durationProgressSummary = "",
            issues = emptySet(),
            durationIssues = emptySet(),
            durationMergedPullRequests = emptySet(),
            milestones = emptySet(),
            isTagMilestoneAssignees = false,
        )
    }
}
