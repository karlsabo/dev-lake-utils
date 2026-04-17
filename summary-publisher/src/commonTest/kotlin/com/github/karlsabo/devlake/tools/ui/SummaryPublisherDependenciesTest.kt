package com.github.karlsabo.devlake.tools.ui

import com.github.karlsabo.devlake.tools.ProjectSummaryHolder
import com.github.karlsabo.devlake.tools.SummaryBuilder
import com.github.karlsabo.devlake.tools.SummaryMessagePublisher
import com.github.karlsabo.devlake.tools.SummaryPublisherComponent
import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.devlake.tools.SummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.devlake.tools.loadSummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.pagerduty.PagerDutyApiRestConfig
import com.github.karlsabo.text.TextSummarizerOpenAiConfig
import com.github.karlsabo.tools.model.ProjectSummary
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummaryPublisherDependenciesTest {

    @Test
    fun loadSummaryPublisherDependenciesUsesProvidedComponentFactory() {
        val config = SummaryPublisherConfig(zapierSummaryUrl = "https://hooks.example.com/summary")
        val usersConfig = UsersConfig(
            users = listOf(
                User(
                    id = "user-1",
                    email = "user-1@example.com",
                    name = "User One",
                    gitHubId = "user-one",
                ),
            ),
        )
        val linearApiConfig = LinearApiRestConfig(token = "linear-token")
        val gitHubApiConfig = GitHubApiRestConfig(token = "github-token")
        val pagerDutyApiConfig = PagerDutyApiRestConfig(apiKey = "pagerduty-token")
        val textSummarizerConfig = TextSummarizerOpenAiConfig(apiKey = "openai-token")
        val dependencies = SummaryPublisherDependencies(
            summaryBuilder = RecordingSummaryBuilder(emptySummary()),
            summaryPublisher = NoOpSummaryPublisher,
        )

        val loadedDependencies = loadSummaryPublisherDependencies(
            config = config,
            loadUsersConfig = { usersConfig },
            loadLinearApiConfig = { linearApiConfig },
            loadGitHubApiConfig = { gitHubApiConfig },
            loadPagerDutyApiConfig = { pagerDutyApiConfig },
            loadTextSummarizerConfig = { textSummarizerConfig },
            componentFactory = {
                    providedConfig,
                    providedUsersConfig,
                    providedLinearApiConfig,
                    providedGitHubApiConfig,
                    providedPagerDutyApiConfig,
                    providedTextSummarizerConfig,
                ->
                assertEquals(config, providedConfig)
                assertSame(usersConfig, providedUsersConfig)
                assertEquals(linearApiConfig, providedLinearApiConfig)
                assertEquals(gitHubApiConfig, providedGitHubApiConfig)
                assertEquals(pagerDutyApiConfig, providedPagerDutyApiConfig)
                assertEquals(textSummarizerConfig, providedTextSummarizerConfig)
                object : SummaryPublisherComponent(
                    providedConfig,
                    providedUsersConfig,
                    providedLinearApiConfig,
                    providedGitHubApiConfig,
                    providedPagerDutyApiConfig,
                    providedTextSummarizerConfig,
                ) {
                    override val dependencies = dependencies
                }
            },
        )

        assertSame(dependencies, loadedDependencies)
    }

    @Test
    fun loadSummaryPreviewUsesLoadedDependencies() = runBlocking {
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
            loadDependencies = { providedConfig ->
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
    fun loadConfigurationCreatesMissingTemplatesWhenBootstrapFails() {
        val tempDir = createTempDir()
        val summaryConfigPath = Path(tempDir, "summary-publisher-config.json")
        val textSummarizerPath = Path(tempDir, "text-summarizer-config.json")
        val linearPath = Path(tempDir, "linear-config.json")
        val gitHubPath = Path(tempDir, "github-config.json")
        val pagerDutyPath = Path(tempDir, "pagerduty-config.json")
        val createdPaths = mutableListOf<Path>()
        val state = SummaryPublisherState()

        try {
            val templates = listOf(
                template(
                    path = summaryConfigPath,
                    message = "\nCreating new configuration.\n Please update the configuration file:\n$summaryConfigPath.",
                    createdPaths = createdPaths,
                ),
                template(
                    path = textSummarizerPath,
                    message = "Please update the configuration file:\n$textSummarizerPath.",
                    createdPaths = createdPaths,
                ),
                template(
                    path = linearPath,
                    message = "Please update the configuration file:\n$linearPath.",
                    createdPaths = createdPaths,
                ),
                template(
                    path = gitHubPath,
                    message = "Please update the configuration file:\n$gitHubPath.",
                    createdPaths = createdPaths,
                ),
                template(
                    path = pagerDutyPath,
                    message = "Please update the configuration file:\n$pagerDutyPath.",
                    createdPaths = createdPaths,
                ),
            )

            loadConfiguration(
                state = state,
                configFilePath = summaryConfigPath,
                loadConfig = { throw IllegalStateException("missing config") },
                loadDependencies = { error("Dependencies should not load when config loading fails") },
                buildErrorMessage = { error ->
                    buildConfigurationErrorMessage(
                        error = error,
                        templates = templates,
                    )
                },
            )

            assertEquals(false, state.isConfigLoaded)
            assertEquals(true, state.isDisplayErrorDialog)
            assertEquals(
                "Failed to load configuration: java.lang.IllegalStateException: missing config." +
                        "\nCreating new configuration.\n Please update the configuration file:\n$summaryConfigPath." +
                        "Please update the configuration file:\n$textSummarizerPath." +
                        "Please update the configuration file:\n$linearPath." +
                        "Please update the configuration file:\n$gitHubPath." +
                        "Please update the configuration file:\n$pagerDutyPath.",
                state.errorMessage,
            )
            assertEquals(
                listOf(summaryConfigPath, textSummarizerPath, linearPath, gitHubPath, pagerDutyPath),
                createdPaths,
            )
            createdPaths.forEach { path ->
                assertTrue(SystemFileSystem.exists(path))
            }
        } finally {
            deleteRecursively(tempDir)
        }
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

    private fun createTempDir(): Path {
        val name = "summary-publisher-test-${Random.nextLong().toULong().toString(16)}"
        val path = Path(SystemTemporaryDirectory, name)
        SystemFileSystem.createDirectories(path)
        return path
    }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    private fun template(
        path: Path,
        message: String,
        createdPaths: MutableList<Path>,
    ) = SummaryPublisherBootstrapTemplate(
        path = path,
        missingMessage = message,
        createIfMissing = {
            createdPaths += path
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.writeString("created for test")
            }
        },
    )

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
