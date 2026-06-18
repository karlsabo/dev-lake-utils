package com.github.karlsabo.devlake.metrics.ui

import com.github.karlsabo.devlake.metrics.UserMetricPublisherComponent
import com.github.karlsabo.devlake.metrics.UserMetricPublisherConfig
import com.github.karlsabo.devlake.metrics.UserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.UserMetricPublisherState
import com.github.karlsabo.devlake.metrics.loadUserMetricPublisherDependencies
import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.devlake.metrics.model.toSlackMarkdown
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.devlake.metrics.service.UserMetricMessagePublisherService
import com.github.karlsabo.devlake.metrics.service.UserMetricsService
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.projectmanagement.IssueFilter
import com.github.karlsabo.projectmanagement.ProjectComment
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.projectmanagement.ProjectMilestone
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

class UserMetricPublisherDependenciesTest {

    @Test
    fun loadUserMetricPublisherDependenciesUsesProvidedComponentFactory() {
        val config = UserMetricPublisherConfig(zapierMetricUrl = "https://hooks.example.com/metrics")
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
        val dependencies = testDependencies(
            usersConfig = usersConfig,
            projectManagementApi = NoOpProjectManagementApi(),
            gitHubApi = NoOpGitHubApi(),
            metricsService = RecordingMetricsService(
                userMetrics(
                    userId = "user-1",
                    email = "user-1@example.com",
                    pullRequestsYearToDateCount = 0u,
                    prReviewCountYtd = 0u,
                    issuesClosedYearToDateCount = 0u,
                ),
            ),
            messagePublisherService = NoOpMessagePublisherService(config),
        )

        val loadedDependencies = loadUserMetricPublisherDependencies(
            config = config,
            loadUsersConfig = { usersConfig },
            loadLinearApiConfig = { linearApiConfig },
            loadGitHubApiConfig = { gitHubApiConfig },
            componentFactory = {
                    providedConfig,
                    providedUsersConfig,
                    providedLinearApiConfig,
                    providedGitHubApiConfig,
                ->
                assertEquals(config, providedConfig)
                assertSame(usersConfig, providedUsersConfig)
                assertEquals(linearApiConfig, providedLinearApiConfig)
                assertEquals(gitHubApiConfig, providedGitHubApiConfig)
                object : UserMetricPublisherComponent(
                    providedConfig,
                    providedUsersConfig,
                    providedLinearApiConfig,
                    providedGitHubApiConfig,
                ) {
                    override val dependencies = dependencies
                }
            },
        )

        assertSame(dependencies, loadedDependencies)
    }

    @Test
    fun loadUserMetricPublisherDependenciesExposesDirectDependencies() {
        val usersConfig = UsersConfig(emptyList())
        val projectManagementApi = NoOpProjectManagementApi()
        val gitHubApi = NoOpGitHubApi()
        val metricsService = RecordingMetricsService(
            userMetrics(
                userId = "user-1",
                email = "user-1@example.com",
                pullRequestsYearToDateCount = 0u,
                prReviewCountYtd = 0u,
                issuesClosedYearToDateCount = 0u,
            ),
        )
        val messagePublisherService = NoOpMessagePublisherService(UserMetricPublisherConfig())

        val dependencies = UserMetricPublisherDependencies(
            usersConfig = usersConfig,
            projectManagementApi = projectManagementApi,
            gitHubApi = gitHubApi,
            metricsService = metricsService,
            messagePublisherService = messagePublisherService,
        )

        assertSame(usersConfig, dependencies.usersConfig)
        assertSame(projectManagementApi, dependencies.projectManagementApi)
        assertSame(gitHubApi, dependencies.gitHubApi)
        assertSame(metricsService, dependencies.metricsService)
        assertSame(messagePublisherService, dependencies.messagePublisherService)
    }

    @Test
    fun loadMetricsPreviewUsesGeneratedDependencies() = runBlocking {
        val user = User(
            id = "user-1",
            email = "user-1@example.com",
            name = "User One",
            gitHubId = "user-one",
        )
        val config = UserMetricPublisherConfig(
            organizationIds = listOf("test-org"),
            userIds = listOf(user.id),
            metricInformationPostfix = "Configured postfix",
        )
        val fakeProjectManagementApi = NoOpProjectManagementApi()
        val fakeGitHubApi = NoOpGitHubApi()
        val expectedMetrics = userMetrics(
            userId = user.id,
            email = user.email!!,
            pullRequestsYearToDateCount = 5u,
            prReviewCountYtd = 3u,
            issuesClosedYearToDateCount = 8u,
        )
        val recordingMetricsService = RecordingMetricsService(expectedMetrics)
        val dependencies = testDependencies(
            usersConfig = UsersConfig(listOf(user)),
            projectManagementApi = fakeProjectManagementApi,
            gitHubApi = fakeGitHubApi,
            metricsService = recordingMetricsService,
            messagePublisherService = NoOpMessagePublisherService(config),
        )
        val state = UserMetricPublisherState()

        loadConfiguration(
            state = state,
            runtime = UserMetricPublisherRuntime(
                loadConfig = { config },
                loadDependencies = { providedConfig ->
                    assertEquals(config, providedConfig)
                    dependencies
                },
            ),
        )
        loadMetrics(state)

        assertLoadedMetrics(
            state,
            LoadedMetricsExpectation(
                config = config,
                dependencies = dependencies,
                expectedMetrics = expectedMetrics,
                recordingMetricsService = recordingMetricsService,
                apis = MetricsApis(fakeProjectManagementApi, fakeGitHubApi),
            ),
        )
    }

    @Test
    fun loadConfigurationCreatesDefaultConfigWhenConfigLoadingFailsAndFileIsMissing() {
        val configFilePath = Path("/tmp/user-metrics-publisher-config.json")
        val createdConfigs = mutableListOf<UserMetricPublisherConfig>()
        val state = UserMetricPublisherState()

        loadConfiguration(
            state = state,
            runtime = testRuntime(
                configFilePath = configFilePath,
                loadConfig = { throw IllegalStateException("missing config") },
                loadDependencies = {
                    error("Dependencies should not load when config loading fails")
                },
                configFileExists = { false },
                saveDefaultConfig = { createdConfigs += it },
            ),
        )

        assertEquals(false, state.isLoadingConfig)
        assertEquals(true, state.isDisplayErrorDialog)
        assertEquals(
            buildConfigurationErrorMessage(
                error = IllegalStateException("missing config"),
                configFilePath = configFilePath,
            ),
            state.errorMessage,
        )
        assertEquals(listOf(UserMetricPublisherConfig()), createdConfigs)
    }

    @Test
    fun loadConfigurationDoesNotCreateDefaultConfigWhenDependenciesFail() {
        val configFilePath = Path("/tmp/user-metrics-publisher-config.json")
        val createdConfigs = mutableListOf<UserMetricPublisherConfig>()
        val config = UserMetricPublisherConfig(zapierMetricUrl = "https://hooks.example.com/metrics")
        val state = UserMetricPublisherState()

        loadConfiguration(
            state = state,
            runtime = testRuntime(
                configFilePath = configFilePath,
                loadConfig = { config },
                loadDependencies = {
                    throw IllegalStateException("dependency failure")
                },
                configFileExists = { false },
                saveDefaultConfig = { createdConfigs += it },
            ),
        )

        assertEquals(false, state.isLoadingConfig)
        assertEquals(true, state.isDisplayErrorDialog)
        assertEquals(
            buildDependenciesErrorMessage(
                error = IllegalStateException("dependency failure"),
                configFilePath = configFilePath,
            ),
            state.errorMessage,
        )
        assertEquals(emptyList(), createdConfigs)
        assertEquals(config, state.config)
    }

    @Test
    fun publishMetricsUsesInjectedMessagePublisher() = runBlocking {
        val metricOne = userMetrics(
            userId = "user-1",
            email = "user-1@example.com",
            pullRequestsYearToDateCount = 5u,
            prReviewCountYtd = 3u,
            issuesClosedYearToDateCount = 8u,
        )
        val metricTwo = userMetrics(
            userId = "user-2",
            email = "user-2@example.com",
            pullRequestsYearToDateCount = 7u,
            prReviewCountYtd = 4u,
            issuesClosedYearToDateCount = 9u,
        )
        val recordingPublisher = RecordingMessagePublisherService()
        val state = publishingState(
            metrics = listOf(metricOne, metricTwo),
            messagePublisherService = recordingPublisher,
        )

        publishMetrics(state)

        assertEquals(
            listOf(
                expectedSlackMessage(metricOne),
                expectedSlackMessage(metricTwo),
            ),
            recordingPublisher.messages,
        )
        assertEquals("Message sent!", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
    }

    @Test
    fun publishMetricsContinuesAfterFailedSendAndReportsFailure() = runBlocking {
        val metricOne = userMetrics(
            userId = "user-1",
            email = "user-1@example.com",
            pullRequestsYearToDateCount = 5u,
            prReviewCountYtd = 3u,
            issuesClosedYearToDateCount = 8u,
        )
        val metricTwo = userMetrics(
            userId = "user-2",
            email = "user-2@example.com",
            pullRequestsYearToDateCount = 7u,
            prReviewCountYtd = 4u,
            issuesClosedYearToDateCount = 9u,
        )
        val metricThree = userMetrics(
            userId = "user-3",
            email = "user-3@example.com",
            pullRequestsYearToDateCount = 11u,
            prReviewCountYtd = 6u,
            issuesClosedYearToDateCount = 10u,
        )
        val recordingPublisher = RecordingMessagePublisherService(results = listOf(true, false, true))
        val state = publishingState(
            metrics = listOf(metricOne, metricTwo, metricThree),
            messagePublisherService = recordingPublisher,
        )

        publishMetrics(state)

        assertEquals(
            listOf(
                expectedSlackMessage(metricOne),
                expectedSlackMessage(metricTwo),
                expectedSlackMessage(metricThree),
            ),
            recordingPublisher.messages,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
    }

    @Test
    fun publishMetricsContinuesAfterThrownSendAndReportsFailure() = runBlocking {
        val metricOne = userMetrics(
            userId = "user-1",
            email = "user-1@example.com",
            pullRequestsYearToDateCount = 5u,
            prReviewCountYtd = 3u,
            issuesClosedYearToDateCount = 8u,
        )
        val metricTwo = userMetrics(
            userId = "user-2",
            email = "user-2@example.com",
            pullRequestsYearToDateCount = 7u,
            prReviewCountYtd = 4u,
            issuesClosedYearToDateCount = 9u,
        )
        val metricThree = userMetrics(
            userId = "user-3",
            email = "user-3@example.com",
            pullRequestsYearToDateCount = 11u,
            prReviewCountYtd = 6u,
            issuesClosedYearToDateCount = 10u,
        )
        val recordingPublisher = ThrowingMessagePublisher(throwOnCallNumbers = setOf(2))
        val state = publishingState(
            metrics = listOf(metricOne, metricTwo, metricThree),
            messagePublisherService = recordingPublisher,
        )

        publishMetrics(state)

        assertEquals(
            listOf(
                expectedSlackMessage(metricOne),
                expectedSlackMessage(metricTwo),
                expectedSlackMessage(metricThree),
            ),
            recordingPublisher.messages,
        )
        assertEquals("Failed to send message", state.publishButtonText)
        assertEquals(false, state.publishButtonEnabled)
    }
}

private fun assertLoadedMetrics(
    state: UserMetricPublisherState,
    expectation: LoadedMetricsExpectation,
) {
    val config = expectation.config
    val metricsService = expectation.recordingMetricsService
    assertEquals(config, state.config)
    assertSame(expectation.dependencies, state.dependencies)
    assertSame(expectation.dependencies.messagePublisherService, state.dependencies?.messagePublisherService)
    assertEquals(listOf(expectation.expectedMetrics), state.metrics)
    assertEquals(listOf(expectation.expectedMetrics.userId), metricsService.requestedUserIds)
    assertEquals(listOf(config.organizationIds), metricsService.requestedOrganizationIds)
    assertSame(expectation.apis.projectManagementApi, metricsService.projectManagementApi)
    assertSame(expectation.apis.gitHubApi, metricsService.gitHubApi)
    assertEquals(expectedPreviewText(expectation.expectedMetrics, config), state.metricsPreviewText)
}

private data class LoadedMetricsExpectation(
    val config: UserMetricPublisherConfig,
    val dependencies: UserMetricPublisherDependencies,
    val expectedMetrics: UserMetrics,
    val recordingMetricsService: RecordingMetricsService,
    val apis: MetricsApis,
)

private data class MetricsApis(
    val projectManagementApi: ProjectManagementApi,
    val gitHubApi: GitHubApi,
)

private fun expectedPreviewText(
    expectedMetrics: UserMetrics,
    config: UserMetricPublisherConfig,
): String = buildString {
    appendLine()
    appendLine(expectedMetrics.userId)
    append(expectedMetrics.toSlackMarkdown())
    appendLine()
    append(config.metricInformationPostfix)
}

private fun testRuntime(
    configFilePath: Path,
    loadConfig: () -> UserMetricPublisherConfig,
    loadDependencies: (UserMetricPublisherConfig) -> UserMetricPublisherDependencies,
    configFileExists: (Path) -> Boolean,
    saveDefaultConfig: (UserMetricPublisherConfig) -> Unit,
): UserMetricPublisherRuntime = UserMetricPublisherRuntime(
    configFilePath = configFilePath,
    loadConfig = loadConfig,
    loadDependencies = loadDependencies,
    configFileStore = UserMetricPublisherConfigFileStore(
        exists = configFileExists,
        saveDefault = saveDefaultConfig,
    ),
)

private fun userMetrics(
    userId: String,
    email: String,
    pullRequestsYearToDateCount: UInt,
    prReviewCountYtd: UInt,
    issuesClosedYearToDateCount: UInt,
): UserMetrics = UserMetrics(
    userId = userId,
    email = email,
    pullRequestsPastWeek = emptyList(),
    pullRequestsYearToDateCount = pullRequestsYearToDateCount,
    prReviewCountYtd = prReviewCountYtd,
    issuesClosedLastWeek = emptyList(),
    issuesClosedYearToDateCount = issuesClosedYearToDateCount,
)

private fun expectedSlackMessage(metric: UserMetrics): SlackMessage = SlackMessage(
    userEmail = metric.email,
    message = buildMetricMessage(metric, "Configured postfix"),
)

private fun publishingState(
    metrics: List<UserMetrics>,
    messagePublisherService: UserMetricMessagePublisherService,
): UserMetricPublisherState = UserMetricPublisherState().apply {
    config = UserMetricPublisherConfig(metricInformationPostfix = "Configured postfix")
    dependencies = testDependencies(
        usersConfig = UsersConfig(emptyList()),
        projectManagementApi = NoOpProjectManagementApi(),
        gitHubApi = NoOpGitHubApi(),
        metricsService = RecordingMetricsService(metrics.first()),
        messagePublisherService = messagePublisherService,
    )
    this.metrics = metrics
}

private fun testDependencies(
    usersConfig: UsersConfig,
    projectManagementApi: ProjectManagementApi,
    gitHubApi: GitHubApi,
    metricsService: UserMetricsService,
    messagePublisherService: UserMetricMessagePublisherService,
): UserMetricPublisherDependencies = UserMetricPublisherDependencies(
    usersConfig = usersConfig,
    projectManagementApi = projectManagementApi,
    gitHubApi = gitHubApi,
    metricsService = metricsService,
    messagePublisherService = messagePublisherService,
)

private class RecordingMetricsService(
    private val metrics: UserMetrics,
) : UserMetricsService() {
    val requestedUserIds = mutableListOf<String>()
    val requestedOrganizationIds = mutableListOf<List<String>>()
    var projectManagementApi: ProjectManagementApi? = null
        private set
    var gitHubApi: GitHubPullRequestSearchApi? = null
        private set

    override suspend fun createUserMetrics(
        user: User,
        organizationIds: List<String>,
        projectManagementApi: ProjectManagementApi,
        gitHubApi: GitHubPullRequestSearchApi,
    ): UserMetrics {
        requestedUserIds += user.id
        requestedOrganizationIds += organizationIds
        this.projectManagementApi = projectManagementApi
        this.gitHubApi = gitHubApi
        return metrics
    }
}

private open class RecordingMessagePublisherService(
    config: UserMetricPublisherConfig,
) : UserMetricMessagePublisherService(config) {
    constructor() : this(UserMetricPublisherConfig())

    constructor(
        results: List<Boolean>,
        config: UserMetricPublisherConfig = UserMetricPublisherConfig(),
    ) : this(config) {
        remainingResults.addAll(results)
    }

    val messages = mutableListOf<SlackMessage>()
    private val remainingResults = ArrayDeque<Boolean>()

    override suspend fun publishMessage(message: SlackMessage): Boolean {
        messages += message
        return if (remainingResults.isEmpty()) {
            true
        } else {
            remainingResults.removeFirst()
        }
    }
}

private class NoOpMessagePublisherService(
    config: UserMetricPublisherConfig,
) : UserMetricMessagePublisherService(config) {
    override suspend fun publishMessage(message: SlackMessage): Boolean = true
}

private class ThrowingMessagePublisher(
    private val throwOnCallNumbers: Set<Int>,
) : UserMetricMessagePublisherService(UserMetricPublisherConfig()) {
    val messages = mutableListOf<SlackMessage>()
    private var callCount = 0

    override suspend fun publishMessage(message: SlackMessage): Boolean {
        callCount += 1
        messages += message
        if (callCount in throwOnCallNumbers) {
            error("boom")
        }
        return true
    }
}

private class NoOpProjectManagementApi : ProjectManagementApi {
    override suspend fun getIssues(issueKeys: List<String>): List<ProjectIssue> = emptyList()

    override suspend fun getChildIssues(issueKeys: List<String>): List<ProjectIssue> = emptyList()

    override suspend fun getDirectChildIssues(parentKey: String): List<ProjectIssue> = emptyList()

    override suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment> = emptyList()

    override suspend fun getIssuesResolved(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): List<ProjectIssue> = emptyList()

    override suspend fun getIssuesResolvedCount(
        user: User,
        startDate: Instant,
        endDate: Instant,
    ): UInt = 0u

    override suspend fun getIssuesByFilter(filter: IssueFilter): List<ProjectIssue> = emptyList()

    override suspend fun getMilestones(projectId: String): List<ProjectMilestone> = emptyList()

    override suspend fun getMilestoneIssues(milestoneId: String): List<ProjectIssue> = emptyList()
}

private class NoOpGitHubApi : GitHubApi {
    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt = 0u

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt = 0u

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> = emptyList()

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> = emptyList()

    override suspend fun listNotifications(): List<Notification> = emptyList()

    override suspend fun getPullRequestByUrl(url: String): PullRequest = PullRequest(url = url)

    override suspend fun approvePullRequestByUrl(url: String, body: String?) = Unit

    override suspend fun markNotificationAsDone(threadId: String) = Unit

    override suspend fun unsubscribeFromNotification(threadId: String) = Unit

    override suspend fun hasAnyApprovedReview(url: String): Boolean = false

    override suspend fun getOpenPullRequestsByAuthor(
        organizationIds: List<String>,
        author: String,
    ): List<Issue> = emptyList()

    override suspend fun getCheckRunsForRef(
        owner: String,
        repo: String,
        ref: String,
    ): CheckRunSummary = CheckRunSummary(
        total = 0,
        passed = 0,
        failed = 0,
        inProgress = 0,
        status = CiStatus.PENDING,
    )

    override suspend fun getReviewSummary(
        owner: String,
        repo: String,
        prNumber: Int,
    ): ReviewSummary = ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())

    override suspend fun submitReview(
        prApiUrl: String,
        event: ReviewStateValue,
        reviewComment: String?,
    ) = Unit
}
