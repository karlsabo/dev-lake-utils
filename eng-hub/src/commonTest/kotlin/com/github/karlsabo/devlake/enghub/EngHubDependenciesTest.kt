package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.screen.EngHubPane
import com.github.karlsabo.devlake.enghub.screen.initialEngHubPane
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubDesktopServices
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubGitHubServices
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubWorktreeServices
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubConfigStore
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretFileWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.notifications.SaveIgnoredNotificationThreadRequest
import com.github.karlsabo.system.DesktopLauncher
import com.github.karlsabo.tools.lenientJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class EngHubDependenciesTest {

    @Test
    fun malformedGitHubConfigurationRecoversAsFreshSettings() {
        val directory = temporaryGitHubDirectory()
        val configPath = Path(directory, "github-config.json")
        writeGitHubTestFile(configPath, "invalid")
        try {
            val loaded = loadGitHubSettingsForEngHub(configPath)

            assertEquals(GitHubConfig(""), loaded.config)
            assertEquals(GitHubSecret(""), loaded.secret)
        } finally {
            deleteGitHubTestDirectory(directory)
        }
    }

    @Test
    fun malformedGitHubPrimaryRecoversTheValidBackupForSettings() {
        val directory = temporaryGitHubDirectory()
        val configPath = Path(directory, "github-config.json")
        val secretPath = Path(directory, "github-secret.json")
        val backupConfig = GitHubConfig(secretPath.toString())
        writeGitHubTestFile(configPath, "invalid")
        writeGitHubTestFile(Path("$configPath.bak"), lenientJson.encodeToString(backupConfig))
        writeGitHubTestFile(secretPath, lenientJson.encodeToString(GitHubSecret("backup-token")))
        try {
            val loaded = loadGitHubSettingsForEngHub(configPath)

            assertEquals(backupConfig, loaded.config)
            assertEquals(GitHubSecret("backup-token"), loaded.secret)
        } finally {
            deleteGitHubTestDirectory(directory)
        }
    }

    @Test
    fun unreadableGitHubSecretKeepsItsPathButNeverLoadsTheSecretBackup() {
        val directory = temporaryGitHubDirectory()
        val configPath = Path(directory, "github-config.json")
        val secretPath = Path(directory, "github-secret.json")
        val config = GitHubConfig(secretPath.toString())
        writeGitHubTestFile(configPath, lenientJson.encodeToString(config))
        writeGitHubTestFile(secretPath, "invalid")
        writeGitHubTestFile(Path("$secretPath.bak"), lenientJson.encodeToString(GitHubSecret("backup-token")))
        try {
            val loaded = loadGitHubSettingsForEngHub(configPath)

            assertEquals(config, loaded.config)
            assertEquals(GitHubSecret(""), loaded.secret)
        } finally {
            deleteGitHubTestDirectory(directory)
        }
    }

    @Test
    fun rejectsEveryEngHubTransactionPathAsTheGitHubSecretBeforeMutation() = runBlocking {
        val directory = temporaryGitHubDirectory()
        val engHubPath = Path(directory, "eng-hub-config.json")
        val gitHubPath = Path(directory, "github-config.json")
        val originalConfig = EngHubConfig(gitHubAuthor = "octocat")
        val originalGitHubConfig = GitHubConfig(Path(directory, "original-secret.json").toString())
        val protectedContents = mapOf(
            engHubPath to lenientJson.encodeToString(originalConfig),
            Path("$engHubPath.new") to "pending contents",
            Path("$engHubPath.bak") to "backup contents",
        )
        protectedContents.forEach(::writeGitHubTestFile)
        writeGitHubTestFile(gitHubPath, lenientJson.encodeToString(originalGitHubConfig))
        var secretWrites = 0
        val configStore = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { _, _ -> secretWrites++ },
        )
        val loadedGitHubConfig = LoadedGitHubConfig(originalGitHubConfig, GitHubSecret("existing-token"))

        try {
            val dependencies = loadEngHubDependencies(
                loadConfig = { originalConfig },
                gitHubConfigFilePath = gitHubPath,
                loadGitHubSettingsConfig = { loadedGitHubConfig },
                componentFactory = ::testEngHubComponent,
                gitHubSettingsInfrastructure = GitHubSettingsInfrastructure(
                    filePicker = RecordingFilePicker(),
                    configStore = configStore,
                    protectedPaths = protectedContents.keys.toList(),
                ),
            )

            protectedContents.keys.forEach { alias ->
                dependencies.settingsViewModel.gitHubTokenSettings.updateSecretPath(alias.toString())
                dependencies.settingsViewModel.flushPendingEdits()

                assertTrue(dependencies.settingsViewModel.uiState.value.gitHubAccessReady)
                protectedContents.forEach { (path, contents) -> assertEquals(contents, readGitHubTestFile(path)) }
                assertEquals(originalGitHubConfig, lenientJson.decodeFromString(readGitHubTestFile(gitHubPath)))
            }

            assertEquals(0, secretWrites)
            assertFalse(SystemFileSystem.exists(Path("$gitHubPath.new")))
            assertFalse(SystemFileSystem.exists(Path("$gitHubPath.bak")))
        } finally {
            deleteGitHubTestDirectory(directory)
        }
    }

    @Test
    fun loadEngHubViewModelUsesProvidedDependencies() = runBlocking {
        val config = EngHubConfig(
            organizationIds = listOf("test-org"),
            repositoriesBaseDir = "/tmp/repos",
            gitHubAuthor = "test-user",
        )
        val gitHubApiConfig = GitHubApiRestConfig(token = "test-token")
        val fakeGitHubApi = RecordingGitHubApi()
        val notificationService = GitHubNotificationService(fakeGitHubApi)
        val fakeGitWorktreeApi = RecordingGitWorktreeApi()
        val fakeDesktopLauncher = RecordingDesktopLauncher()
        val fakeNotificationIgnoreStore = RecordingNotificationIgnoreStore()
        val providedViewModel = com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel(
            gitHubServices = EngHubGitHubServices(
                api = fakeGitHubApi,
                notificationService = notificationService,
            ),
            worktreeServices = EngHubWorktreeServices(
                gitWorktreeApi = fakeGitWorktreeApi,
                worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = fakeGitWorktreeApi),
                directoryPicker = RecordingDirectoryPicker(),
                configWriter = RecordingEngHubConfigWriter(),
            ),
            desktopServices = EngHubDesktopServices(fakeDesktopLauncher),
            config = config,
            notificationIgnoreStore = fakeNotificationIgnoreStore,
        )

        val viewModel = loadEngHubDependencies(
            loadConfig = { config },
            loadGitHubSettingsConfig = { loadedGitHubConfig(gitHubApiConfig.token) },
            componentFactory = { providedConfig, providedGitHubApiConfig ->
                assertSame(config, providedConfig)
                assertEquals(gitHubApiConfig, providedGitHubApiConfig)
                object : EngHubComponent(providedConfig, providedGitHubApiConfig) {
                    override val viewModel = providedViewModel
                    override val directoryPicker = RecordingDirectoryPicker()
                }
            },
        ).viewModel

        viewModel.openInBrowser("https://example.com/pr/1")
        assertEquals(
            listOf("https://example.com/pr/1"),
            fakeDesktopLauncher.openedUrls.awaitValue(),
        )

        viewModel.checkoutAndOpen("test-org/test-repo", "feature-branch")
        assertEquals(
            listOf(EnsureRepositoryCall("/tmp/repos/test-repo", "https://github.com/test-org/test-repo.git")),
            fakeGitWorktreeApi.ensureRepositoryCalls.awaitValue(),
        )
        assertEquals(
            listOf(EnsureWorktreeCall("/tmp/repos/test-repo", "feature-branch")),
            fakeGitWorktreeApi.ensureWorktreeCalls.awaitValue(),
        )

        viewModel.markNotificationDone(testNotificationUiState())
        assertEquals(listOf("thread-1"), fakeGitHubApi.markedDoneThreadIds.awaitValue())
    }

    @Test
    fun missingEngHubConfigLoadsDefaultSettingsDraft() {
        val config = EngHubConfig()
        val gitHubApiConfig = GitHubApiRestConfig(token = "")
        val fakeGitHubApi = RecordingGitHubApi()
        val fakeNotificationIgnoreStore = RecordingNotificationIgnoreStore()
        val fakeGitWorktreeApi = RecordingGitWorktreeApi()
        val providedViewModel = com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel(
            gitHubServices = EngHubGitHubServices(
                api = fakeGitHubApi,
                notificationService = GitHubNotificationService(fakeGitHubApi),
            ),
            worktreeServices = EngHubWorktreeServices(
                gitWorktreeApi = fakeGitWorktreeApi,
                worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = fakeGitWorktreeApi),
                directoryPicker = RecordingDirectoryPicker(),
                configWriter = RecordingEngHubConfigWriter(),
            ),
            desktopServices = EngHubDesktopServices(RecordingDesktopLauncher()),
            config = config,
            notificationIgnoreStore = fakeNotificationIgnoreStore,
        )

        val loadedDependencies = loadEngHubDependencies(
            loadConfig = { null },
            loadGitHubSettingsConfig = { null },
            componentFactory = { providedConfig, providedGitHubApiConfig ->
                assertEquals(config, providedConfig)
                assertEquals(gitHubApiConfig, providedGitHubApiConfig)
                object : EngHubComponent(providedConfig, providedGitHubApiConfig) {
                    override val viewModel = providedViewModel
                    override val directoryPicker = RecordingDirectoryPicker()
                }
            },
        )

        val settings = loadedDependencies.settingsViewModel.uiState.value
        assertEquals(config, loadedDependencies.config)
        assertSame(providedViewModel, loadedDependencies.viewModel)
        assertEquals(emptyList(), settings.organizationIds)
        assertEquals("600", settings.pollIntervalSeconds)
        assertEquals("120", settings.worktreePollIntervalSeconds)
        assertEquals("", settings.repositoriesBaseDir)
        assertEquals("", settings.gitHubAuthor)
        assertEquals("", settings.gitHubTokenPath)
        assertEquals("", settings.gitHubToken.maskedValue)
        assertEquals(EngHubPane.Settings, initialEngHubPane(settings))
    }
}

private fun temporaryGitHubDirectory(): Path {
    val directory = Path(SystemTemporaryDirectory, "eng-hub-github-${Random.nextLong()}")
    SystemFileSystem.createDirectories(directory)
    return directory
}

private fun writeGitHubTestFile(path: Path, text: String) {
    SystemFileSystem.sink(path, false).buffered().use { sink -> sink.writeString(text) }
}

private fun readGitHubTestFile(path: Path): String = SystemFileSystem
    .source(path)
    .buffered()
    .use { source -> source.readString() }

private fun deleteGitHubTestDirectory(directory: Path) {
    SystemFileSystem.list(directory).forEach { path -> SystemFileSystem.delete(path, mustExist = false) }
    SystemFileSystem.delete(directory, mustExist = false)
}

private fun loadedGitHubConfig(token: String) = LoadedGitHubConfig(
    config = GitHubConfig(tokenPath = "/tmp/github-secret.json"),
    secret = GitHubSecret(githubToken = token),
)

private fun testNotificationUiState(): NotificationUiState = NotificationUiState(
    notificationThreadId = "thread-1",
    title = "Notification thread-1",
    reason = "review_requested",
    updatedAtEpochMs = 2_026_052_910_000,
    repositoryFullName = "test-org/test-repo",
    subjectType = "PullRequest",
    htmlUrl = "https://github.com/test-org/test-repo/pull/1",
    apiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
    isPullRequest = true,
    pullRequestNumber = 1,
    unread = true,
    headRef = "feature/test",
)

private data class EnsureRepositoryCall(
    val repoPath: String,
    val cloneUrl: String,
)

private data class EnsureWorktreeCall(
    val repoPath: String,
    val branch: String,
)

private suspend fun <T> MutableStateFlow<List<T>>.awaitValue(): List<T> {
    val values = this
    return withTimeout(2_000.milliseconds) { values.first { it.isNotEmpty() } }
}

private class RecordingGitWorktreeApi : GitWorktreeApi {
    val ensureRepositoryCalls = MutableStateFlow<List<EnsureRepositoryCall>>(emptyList())
    val ensureWorktreeCalls = MutableStateFlow<List<EnsureWorktreeCall>>(emptyList())

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        ensureRepositoryCalls.value += EnsureRepositoryCall(repoPath, cloneUrl)
    }

    override fun ensureWorktree(repoPath: String, branch: String): String {
        ensureWorktreeCalls.value += EnsureWorktreeCall(repoPath, branch)
        return buildWorktreePath(repoPath, branch).value
    }

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        error("Unexpected call")
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false
    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        error("Unexpected call")
    }

    override fun listWorktrees(repoPath: String) = emptyList<com.github.karlsabo.git.Worktree>()

    override fun inferDefaultBranchRef(repoPath: String): String? = null

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> = emptyMap()

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) = Unit
}

private fun testEngHubComponent(config: EngHubConfig, gitHubApiConfig: GitHubApiRestConfig): EngHubComponent {
    val gitHubApi = RecordingGitHubApi()
    val gitWorktreeApi = RecordingGitWorktreeApi()
    val viewModel = com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel(
        gitHubServices = EngHubGitHubServices(
            api = gitHubApi,
            notificationService = GitHubNotificationService(gitHubApi),
        ),
        worktreeServices = EngHubWorktreeServices(
            gitWorktreeApi = gitWorktreeApi,
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = gitWorktreeApi),
            directoryPicker = RecordingDirectoryPicker(),
            configWriter = RecordingEngHubConfigWriter(),
        ),
        desktopServices = EngHubDesktopServices(RecordingDesktopLauncher()),
        config = config,
        notificationIgnoreStore = RecordingNotificationIgnoreStore(),
    )
    return object : EngHubComponent(config, gitHubApiConfig) {
        override val viewModel = viewModel
        override val directoryPicker = RecordingDirectoryPicker()
    }
}

private class RecordingDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private class RecordingFilePicker : FilePicker {
    override suspend fun pickFilePath(title: String): String? = null
}

private class RecordingEngHubConfigWriter : EngHubConfigWriter {
    override fun save(config: EngHubConfig) = Unit
}

private class RecordingDesktopLauncher : DesktopLauncher {
    val openedUrls = MutableStateFlow<List<String>>(emptyList())

    override fun openUrl(url: String) {
        openedUrls.value += url
    }

    override fun openInIdea(projectPath: String) = Unit
}

private class RecordingGitHubApi : GitHubApi {
    val markedDoneThreadIds = MutableStateFlow<List<String>>(emptyList())

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

    override suspend fun markNotificationAsDone(threadId: String) {
        markedDoneThreadIds.value += threadId
    }

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
    ): CheckRunSummary = CheckRunSummary(total = 0, passed = 0, failed = 0, inProgress = 0, status = CiStatus.PENDING)

    override suspend fun getReviewSummary(
        owner: String,
        repo: String,
        prNumber: Int,
    ): ReviewSummary = ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())
}

private class RecordingNotificationIgnoreStore : NotificationIgnoreStore {
    override fun listIgnoredThreadIds(): Set<String> = emptySet()

    override fun listIgnoredThreads(): List<IgnoredNotificationThread> = emptyList()

    override fun saveIgnoredThread(request: SaveIgnoredNotificationThreadRequest) = Unit
}
