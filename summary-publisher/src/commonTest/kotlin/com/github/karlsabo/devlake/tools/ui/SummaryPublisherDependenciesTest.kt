package com.github.karlsabo.devlake.tools.ui

import com.github.karlsabo.devlake.tools.ProjectSummaryHolder
import com.github.karlsabo.devlake.tools.SummaryPublisherComponent
import com.github.karlsabo.devlake.tools.SummaryPublisherComponentInputs
import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.devlake.tools.SummaryPublisherConfigLoaders
import com.github.karlsabo.devlake.tools.SummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.SummaryPublisherState
import com.github.karlsabo.devlake.tools.ZapierSummaryPublisher
import com.github.karlsabo.devlake.tools.loadSummaryPublisherDependencies
import com.github.karlsabo.devlake.tools.service.SummaryBuilderService
import com.github.karlsabo.devlake.tools.service.SummaryMessagePublisherService
import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.dto.Project
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyApiRestConfig
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.text.TextSummarizerOpenAiConfig
import com.github.karlsabo.tools.model.ProjectSummary
import kotlinx.coroutines.runBlocking
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

private fun unexpected(): Nothing = error("unexpected call")

private fun assertComponentInputs(
    expected: SummaryPublisherComponentInputs,
    actual: SummaryPublisherComponentInputs,
) {
    assertEquals(expected.config, actual.config)
    assertSame(expected.usersConfig, actual.usersConfig)
    assertEquals(expected.linearApiConfig, actual.linearApiConfig)
    assertEquals(expected.gitHubApiConfig, actual.gitHubApiConfig)
    assertEquals(expected.pagerDutyApiConfig, actual.pagerDutyApiConfig)
    assertEquals(expected.textSummarizerConfig, actual.textSummarizerConfig)
}

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
            summaryBuilder = emptySummaryBuilderService(config, usersConfig),
            summaryPublisher = summaryMessagePublisherService(config),
        )

        val loadedDependencies = loadSummaryPublisherDependencies(
            config = config,
            configLoaders = SummaryPublisherConfigLoaders(
                loadUsersConfig = { usersConfig },
                loadLinearApiConfig = { linearApiConfig },
                loadGitHubApiConfig = { gitHubApiConfig },
                loadPagerDutyApiConfig = { pagerDutyApiConfig },
                loadTextSummarizerConfig = { textSummarizerConfig },
            ),
            componentFactory = { providedInputs ->
                assertComponentInputs(
                    expected = SummaryPublisherComponentInputs(
                        config = config,
                        usersConfig = usersConfig,
                        linearApiConfig = linearApiConfig,
                        gitHubApiConfig = gitHubApiConfig,
                        pagerDutyApiConfig = pagerDutyApiConfig,
                        textSummarizerConfig = textSummarizerConfig,
                    ),
                    actual = providedInputs,
                )
                object : SummaryPublisherComponent(providedInputs) {
                    override val dependencies = dependencies
                }
            },
        )

        assertSame(dependencies, loadedDependencies)
        assertEquals(SummaryBuilderService::class, loadedDependencies.summaryBuilder::class)
        assertEquals(SummaryMessagePublisherService::class, loadedDependencies.summaryPublisher::class)
    }

    @Test
    fun loadSummaryPreviewUsesLoadedDependencies() = runBlocking {
        val config = SummaryPublisherConfig(
            summaryName = "Test Summary",
            isTerseSummaryUsed = true,
            isMiscellaneousProjectIncluded = false,
        )
        val dependencies = SummaryPublisherDependencies(
            summaryBuilder = emptySummaryBuilderService(
                config = config,
                usersConfig = UsersConfig(users = emptyList()),
            ),
            summaryPublisher = summaryMessagePublisherService(config),
        )
        val expectedSummary = dependencies.summaryBuilder.createSummary()
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
        assertEquals(expectedSummary.toTerseSlackMarkup(), state.topLevelSummary)
        assertEquals(emptyList(), state.projectSummaries)
        assertEquals(false, state.isLoadingSummary)
    }

    @Test
    fun loadConfigurationCreatesMissingTemplatesWhenBootstrapFails() {
        val tempDir = createTempDir()
        val fixture = bootstrapFailureFixture(tempDir)
        val state = SummaryPublisherState()

        try {
            loadConfiguration(
                state = state,
                configFilePath = fixture.paths.summary,
                loadConfig = { error("missing config") },
                loadDependencies = { error("Dependencies should not load when config loading fails") },
                buildErrorMessage = { error ->
                    buildConfigurationErrorMessage(error = error, templates = fixture.templates)
                },
            )

            assertConfigurationBootstrapFailure(state, fixture)
        } finally {
            deleteRecursively(tempDir)
        }
    }

    @Test
    fun publishSummaryUsesInjectedPublisher() = runBlocking {
        val recordingPublisher = RecordingZapierSummaryPublisher()
        val summaryPublisher = SummaryMessagePublisherService(recordingPublisher)
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = emptySummaryBuilderService(),
                summaryPublisher = summaryPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message",
                ),
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-2"),
                    message = "Project two message",
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message", "Project two message"),
                ),
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Message sent!", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
    }

    @Test
    fun publishSummaryReportsFailureWhenPublisherReturnsFalse() = runBlocking {
        val recordingPublisher = RecordingZapierSummaryPublisher(results = listOf(false))
        val summaryPublisher = SummaryMessagePublisherService(recordingPublisher)
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = emptySummaryBuilderService(),
                summaryPublisher = summaryPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message",
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message"),
                ),
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
    }

    @Test
    fun publishSummaryReportsFailureWhenPublisherThrows() = runBlocking {
        val recordingPublisher = RecordingZapierSummaryPublisher(shouldThrow = true)
        val summaryPublisher = SummaryMessagePublisherService(recordingPublisher)
        val state = SummaryPublisherState().apply {
            dependencies = SummaryPublisherDependencies(
                summaryBuilder = emptySummaryBuilderService(),
                summaryPublisher = summaryPublisher,
            )
            topLevelSummary = "Top level summary"
            projectSummaries = listOf(
                ProjectSummaryHolder(
                    projectSummary = emptyProjectSummary("project-1"),
                    message = "Project one message",
                ),
            )
        }

        publishSummary(state)

        assertEquals(
            listOf(
                ZapierProjectSummary(
                    message = "Top level summary",
                    projectMessages = listOf("Project one message"),
                ),
            ),
            recordingPublisher.publishedSummaries,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
        assertEquals(false, state.isSendingSlackMessage)
    }

    private fun emptySummaryBuilderService(
        config: SummaryPublisherConfig = SummaryPublisherConfig(),
        usersConfig: UsersConfig = UsersConfig(users = emptyList()),
    ): SummaryBuilderService = SummaryBuilderService(
        config = config,
        usersConfig = usersConfig,
        projectManagementApi = NoOpProjectManagementApi,
        gitHubApi = NoOpGitHubApi,
        pagerDutyApi = NoOpPagerDutyApi,
        textSummarizer = NoOpTextSummarizer,
    )

    private fun summaryMessagePublisherService(
        config: SummaryPublisherConfig = SummaryPublisherConfig(),
    ): SummaryMessagePublisherService = SummaryMessagePublisherService(
        zapierSummaryPublisher = RecordingZapierSummaryPublisher(config = config),
    )

    private class RecordingZapierSummaryPublisher(
        config: SummaryPublisherConfig = SummaryPublisherConfig(),
        results: List<Boolean> = emptyList(),
        private val shouldThrow: Boolean = false,
    ) : ZapierSummaryPublisher(config) {
        val publishedSummaries = mutableListOf<ZapierProjectSummary>()
        private val remainingResults = ArrayDeque(results)

        override suspend fun publishSummary(summary: ZapierProjectSummary): Boolean {
            publishedSummaries += summary
            if (shouldThrow) {
                error("boom")
            }
            return if (remainingResults.isEmpty()) {
                true
            } else {
                remainingResults.removeFirst()
            }
        }
    }

    private object NoOpProjectManagementApi : ProjectManagementApi {
        override suspend fun getIssues(issueKeys: List<String>) = unexpected()

        override suspend fun getChildIssues(issueKeys: List<String>) = unexpected()

        override suspend fun getDirectChildIssues(parentKey: String) = unexpected()

        override suspend fun getRecentComments(issueKey: String, maxResults: Int) = unexpected()

        override suspend fun getIssuesResolved(
            user: User,
            startDate: kotlinx.datetime.Instant,
            endDate: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun getIssuesResolvedCount(
            user: User,
            startDate: kotlinx.datetime.Instant,
            endDate: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun getIssuesByFilter(filter: com.github.karlsabo.projectmanagement.IssueFilter) = unexpected()

        override suspend fun getMilestones(projectId: String) = unexpected()

        override suspend fun getMilestoneIssues(milestoneId: String) = unexpected()
    }

    private object NoOpGitHubApi : GitHubApi {
        override suspend fun getMergedPullRequestCount(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: kotlinx.datetime.Instant,
            endDate: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun getPullRequestReviewCount(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: kotlinx.datetime.Instant,
            endDate: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun getMergedPullRequests(
            gitHubUserId: String,
            organizationIds: List<String>,
            startDate: kotlinx.datetime.Instant,
            endDate: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun searchPullRequestsByText(
            searchText: String,
            organizationIds: List<String>,
            startDateInclusive: kotlinx.datetime.Instant,
            endDateInclusive: kotlinx.datetime.Instant,
        ) = unexpected()

        override suspend fun listNotifications() = unexpected()

        override suspend fun getPullRequestByUrl(url: String) = unexpected()

        override suspend fun approvePullRequestByUrl(url: String, body: String?) = unexpected()

        override suspend fun markNotificationAsDone(threadId: String) = unexpected()

        override suspend fun unsubscribeFromNotification(threadId: String) = unexpected()

        override suspend fun hasAnyApprovedReview(url: String) = unexpected()

        override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String) = unexpected()

        override suspend fun getCheckRunsForRef(
            owner: String,
            repo: String,
            ref: String,
        ) = unexpected()

        override suspend fun getReviewSummary(
            owner: String,
            repo: String,
            prNumber: Int,
        ) = unexpected()

        override suspend fun submitReview(
            prApiUrl: String,
            event: com.github.karlsabo.github.ReviewStateValue,
            reviewComment: String?,
        ) = unexpected()
    }

    private object NoOpPagerDutyApi : PagerDutyApi {
        override suspend fun getServicePages(
            serviceId: String,
            startTimeInclusive: kotlinx.datetime.Instant,
            endTimeExclusive: kotlinx.datetime.Instant,
        ) = unexpected()
    }

    private object NoOpTextSummarizer : TextSummarizer {
        override suspend fun summarize(text: String) = unexpected()
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

    private fun bootstrapFailureFixture(tempDir: Path): BootstrapFailureFixture {
        val paths = BootstrapConfigPaths(
            summary = Path(tempDir, "summary-publisher-config.json"),
            textSummarizer = Path(tempDir, "text-summarizer-config.json"),
            linear = Path(tempDir, "linear-config.json"),
            gitHub = Path(tempDir, "github-config.json"),
            pagerDuty = Path(tempDir, "pagerduty-config.json"),
        )
        val createdPaths = mutableListOf<Path>()
        return BootstrapFailureFixture(
            paths = paths,
            createdPaths = createdPaths,
            templates = paths.all.map { path ->
                template(
                    path = path,
                    message = missingConfigMessage(path, isSummaryConfig = path == paths.summary),
                    createdPaths = createdPaths,
                )
            },
        )
    }

    private fun assertConfigurationBootstrapFailure(
        state: SummaryPublisherState,
        fixture: BootstrapFailureFixture,
    ) {
        assertEquals(false, state.isConfigLoaded)
        assertEquals(true, state.isDisplayErrorDialog)
        assertEquals(expectedBootstrapFailureMessage(fixture.paths), state.errorMessage)
        assertEquals(fixture.paths.all, fixture.createdPaths)
        fixture.createdPaths.forEach { path ->
            assertTrue(SystemFileSystem.exists(path))
        }
    }

    private fun expectedBootstrapFailureMessage(paths: BootstrapConfigPaths): String {
        val prefix = "Failed to load configuration: java.lang.IllegalStateException: missing config."
        return prefix + paths.all.joinToString(separator = "") { path ->
            missingConfigMessage(path, isSummaryConfig = path == paths.summary)
        }
    }

    private fun missingConfigMessage(path: Path, isSummaryConfig: Boolean): String {
        val updateMessage = "Please update the configuration file:\n$path."
        return if (isSummaryConfig) {
            "\nCreating new configuration.\n $updateMessage"
        } else {
            updateMessage
        }
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

    private data class BootstrapConfigPaths(
        val summary: Path,
        val textSummarizer: Path,
        val linear: Path,
        val gitHub: Path,
        val pagerDuty: Path,
    ) {
        val all = listOf(summary, textSummarizer, linear, gitHub, pagerDuty)
    }

    private data class BootstrapFailureFixture(
        val paths: BootstrapConfigPaths,
        val createdPaths: MutableList<Path>,
        val templates: List<SummaryPublisherBootstrapTemplate>,
    )

    private companion object {
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
