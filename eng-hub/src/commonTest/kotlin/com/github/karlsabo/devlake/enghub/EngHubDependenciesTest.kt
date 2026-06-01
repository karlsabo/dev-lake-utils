package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.state.NotificationUiState
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
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

class EngHubDependenciesTest {

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
            gitHubApi = fakeGitHubApi,
            gitHubNotificationService = notificationService,
            gitWorktreeApi = fakeGitWorktreeApi,
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = fakeGitWorktreeApi),
            desktopLauncher = fakeDesktopLauncher,
            directoryPicker = RecordingDirectoryPicker(),
            configWriter = RecordingEngHubConfigWriter(),
            config = config,
            notificationIgnoreStore = fakeNotificationIgnoreStore,
        )

        val viewModel = loadEngHubViewModel(
            loadConfig = {
                config
            },
            loadGitHubApiConfig = {
                gitHubApiConfig
            },
            componentFactory = { providedConfig, providedGitHubApiConfig ->
                assertSame(config, providedConfig)
                assertSame(gitHubApiConfig, providedGitHubApiConfig)
                object : EngHubComponent(providedConfig, providedGitHubApiConfig) {
                    override val viewModel = providedViewModel
                }
            },
        )

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

        viewModel.markNotificationDone(testNotificationUiState("thread-1"))
        assertEquals(listOf("thread-1"), fakeGitHubApi.markedDoneThreadIds.awaitValue())
    }

    @Test
    fun loadEngHubDependenciesReturnsConfigAndViewModel() {
        val config = EngHubConfig(
            organizationIds = listOf("test-org"),
            repositoriesBaseDir = "/tmp/repos",
            gitHubAuthor = "test-user",
            localRepositories = listOf(
                LocalRepositoryConfig(
                    path = "/workspace/example-service",
                    setupCommands = listOf(
                        "direnv allow",
                        "direnv exec . idea ./",
                    ),
                ),
            ),
        )
        val gitHubApiConfig = GitHubApiRestConfig(token = "test-token")
        val fakeGitHubApi = RecordingGitHubApi()
        val fakeNotificationIgnoreStore = RecordingNotificationIgnoreStore()
        val fakeGitWorktreeApi = RecordingGitWorktreeApi()
        val providedViewModel = com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel(
            gitHubApi = fakeGitHubApi,
            gitHubNotificationService = GitHubNotificationService(fakeGitHubApi),
            gitWorktreeApi = fakeGitWorktreeApi,
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = fakeGitWorktreeApi),
            desktopLauncher = RecordingDesktopLauncher(),
            directoryPicker = RecordingDirectoryPicker(),
            configWriter = RecordingEngHubConfigWriter(),
            config = config,
            notificationIgnoreStore = fakeNotificationIgnoreStore,
        )

        val loadedDependencies = loadEngHubDependencies(
            loadConfig = { config },
            loadGitHubApiConfig = { gitHubApiConfig },
            componentFactory = { providedConfig, providedGitHubApiConfig ->
                assertSame(config, providedConfig)
                assertSame(gitHubApiConfig, providedGitHubApiConfig)
                object : EngHubComponent(providedConfig, providedGitHubApiConfig) {
                    override val viewModel = providedViewModel
                }
            },
        )

        assertSame(config, loadedDependencies.config)
        assertSame(providedViewModel, loadedDependencies.viewModel)
    }
}

private fun testNotificationUiState(@Suppress("SameParameterValue") threadId: String): NotificationUiState = NotificationUiState(
    notificationThreadId = threadId,
    title = "Notification $threadId",
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

private suspend fun <T> MutableStateFlow<List<T>>.awaitValue(): List<T> = withTimeout(2_000.milliseconds) { first { it.isNotEmpty() } }

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

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) = Unit
}

private class RecordingDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
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

    override suspend fun submitReview(
        prApiUrl: String,
        event: ReviewStateValue,
        reviewComment: String?,
    ) = Unit
}

private class RecordingNotificationIgnoreStore : NotificationIgnoreStore {
    override fun listIgnoredThreadIds(): Set<String> = emptySet()

    override fun listIgnoredThreads(): List<IgnoredNotificationThread> = emptyList()

    override fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
        notificationUpdatedAtEpochMs: Long?,
    ) = Unit
}
