package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Label
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.User
import com.github.karlsabo.notifications.NotificationSubscriptionStore
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DEV_LAKE_ROOT = "/repos/dev-lake-utils"
private const val DEV_LAKE_SELECTED_WORKTREE = "/repos/dev-lake-utils-feature-worktree-panel"
private const val DOCS_ROOT = "/repos/docs"
private const val DOCS_SELECTED_WORKTREE = "/repos/docs-feature-notes"

class EngHubViewModelTest {

    @Test
    fun usesPullRequestApiUrlForPullRequestDetailFetches() = runBlocking {
        val issueApiUrl = "https://api.github.com/repos/test-org/test-repo/issues/25843"
        val pullApiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/25843"
        val api = RecordingGitHubApi(
            pullRequestsByUrl = mapOf(
                issueApiUrl to PullRequest(url = issueApiUrl),
                pullApiUrl to PullRequest(
                    url = pullApiUrl,
                    head = PullRequestHead(ref = "feature/pr-url", sha = "abc123"),
                ),
            ),
        )

        val uiState = api.buildPullRequestUiStates(listOf(testIssue(issueApiUrl, pullApiUrl))).single()

        assertEquals(listOf(pullApiUrl), api.pullRequestByUrlCalls)
        assertEquals("feature/pr-url", uiState.headRef)
        assertEquals("3/3 passed", uiState.ciSummaryText)
        assertEquals(pullApiUrl, uiState.apiUrl)
    }

    @Test
    fun fallsBackToIssueApiUrlWhenPullRequestApiUrlIsAbsent() = runBlocking {
        val issueApiUrl = "https://api.github.com/repos/test-org/test-repo/issues/25843"
        val api = RecordingGitHubApi(
            pullRequestsByUrl = mapOf(
                issueApiUrl to PullRequest(
                    url = issueApiUrl,
                    head = PullRequestHead(ref = "feature/fallback", sha = "def456"),
                ),
            ),
        )

        val uiState = api.buildPullRequestUiStates(listOf(testIssue(issueApiUrl, pullApiUrl = null))).single()

        assertEquals(listOf(issueApiUrl), api.pullRequestByUrlCalls)
        assertEquals("feature/fallback", uiState.headRef)
        assertEquals("3/3 passed", uiState.ciSummaryText)
        assertEquals(issueApiUrl, uiState.apiUrl)
    }

    @Test
    fun addingLinkedWorktreePersistsCanonicalRootAndShowsSelectedBranch() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = DEV_LAKE_ROOT,
                selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                worktrees = listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    Worktree(
                        path = DEV_LAKE_SELECTED_WORKTREE,
                        branch = "feature/worktree-panel",
                        commitHash = "def456",
                    ),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)

        val repositories = withTimeout(2_000) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == DEV_LAKE_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals(listOf(DEV_LAKE_ROOT), configWriter.savedConfigs.value.single().localRepositories)
        assertEquals(listOf("dev-lake-utils"), repositories.map { it.name })
        assertEquals(listOf("main", "feature/worktree-panel"), repositories.single().worktrees.map { it.branch })
    }

    @Test
    fun addingLocalRepositoryPreservesExistingRepositoryWorktrees() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = mapOf(
                DEV_LAKE_SELECTED_WORKTREE to RepositoryWorktrees(
                    rootPath = DEV_LAKE_ROOT,
                    selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                    worktrees = listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(
                            path = DEV_LAKE_SELECTED_WORKTREE,
                            branch = "feature/worktree-panel",
                            commitHash = "def456",
                        ),
                    ),
                ),
                DOCS_SELECTED_WORKTREE to RepositoryWorktrees(
                    rootPath = DOCS_ROOT,
                    selectedWorktreePath = DOCS_SELECTED_WORKTREE,
                    worktrees = listOf(
                        Worktree(path = DOCS_ROOT, branch = "main", commitHash = "123abc"),
                        Worktree(
                            path = DOCS_SELECTED_WORKTREE,
                            branch = "feature/notes",
                            commitHash = "456def",
                        ),
                    ),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
        )

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == DEV_LAKE_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        viewModel.addLocalRepository(DOCS_SELECTED_WORKTREE)
        val repositories = withTimeout(2_000) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.size == 2 && repositories.any { it.path == DOCS_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE, DOCS_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals(listOf(DEV_LAKE_ROOT, DOCS_ROOT), configWriter.savedConfigs.value.last().localRepositories)
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch },
        )
        assertEquals(
            listOf("main", "feature/notes"),
            repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch },
        )
    }

    @Test
    fun addingDuplicateLocalRepositorySetsErrorWithoutSavingOrChangingRepositories() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = DEV_LAKE_ROOT,
                selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
                worktrees = listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
            localRepositories = listOf(DEV_LAKE_ROOT),
        )
        val initialRepositories = viewModel.localRepositoriesStateFlow.value

        viewModel.addLocalRepository(DEV_LAKE_SELECTED_WORKTREE)

        val actionError = withTimeout(2_000) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals("Repository already configured: $DEV_LAKE_ROOT", actionError)
        assertEquals(emptyList(), configWriter.savedConfigs.value)
        assertEquals(initialRepositories, viewModel.localRepositoriesStateFlow.value)
    }
}

private class RecordingGitHubApi(
    private val pullRequestsByUrl: Map<String, PullRequest>,
) : GitHubApi {
    val pullRequestByUrlCalls = mutableListOf<String>()

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        pullRequestByUrlCalls += url
        return pullRequestsByUrl.getValue(url)
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun getCheckRunsForRef(owner: String, repo: String, ref: String): CheckRunSummary {
        return CheckRunSummary(total = 3, passed = 3, failed = 0, inProgress = 0, status = CiStatus.PASSED)
    }

    override suspend fun getReviewSummary(owner: String, repo: String, prNumber: Int): ReviewSummary {
        return ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())
    }

    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun listNotifications(): List<Notification> {
        error("Unexpected call")
    }

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        error("Unexpected call")
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        error("Unexpected call")
    }

    override suspend fun submitReview(prApiUrl: String, event: ReviewStateValue, reviewComment: String?) {
        error("Unexpected call")
    }
}

private fun testIssue(issueApiUrl: String, pullApiUrl: String?): Issue {
    val now = Clock.System.now()
    return Issue(
        url = issueApiUrl,
        repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
        id = 25843L,
        number = 25843,
        state = "open",
        title = "Use the PR API URL",
        user = User(
            login = "test-user",
            id = 1L,
            avatarUrl = null,
            url = "https://api.github.com/users/test-user",
            htmlUrl = "https://github.com/test-user",
        ),
        body = null,
        htmlUrl = "https://github.com/test-org/test-repo/pull/25843",
        labels = emptyList<Label>(),
        draft = false,
        createdAt = now,
        updatedAt = now,
        closedAt = null,
        pullRequest = pullApiUrl?.let { PullRequest(url = it) },
        comments = 0,
    )
}

private fun createLocalRepositoryViewModel(
    gitWorktreeApi: RecordingGitWorktreeApi,
    configWriter: RecordingEngHubConfigWriter,
    localRepositories: List<String> = emptyList(),
): EngHubViewModel {
    val gitHubApi = RecordingGitHubApi(emptyMap())
    return EngHubViewModel(
        gitHubApi = gitHubApi,
        gitHubNotificationService = GitHubNotificationService(gitHubApi),
        gitWorktreeApi = gitWorktreeApi,
        desktopLauncher = LocalRepositoryNoOpDesktopLauncher(),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = configWriter,
        config = EngHubConfig(
            pollIntervalMs = 60_000,
            localRepositories = localRepositories,
        ),
        notificationSubscriptionStore = NoOpNotificationSubscriptionStore(),
    )
}

private class RecordingGitWorktreeApi(
    private val repositoryWorktreesBySelectedPath: Map<String, RepositoryWorktrees>,
) : GitWorktreeApi {
    constructor(repositoryWorktrees: RepositoryWorktrees) : this(
        mapOf(repositoryWorktrees.selectedWorktreePath to repositoryWorktrees),
    )

    val resolvedPaths = mutableListOf<String>()

    override fun ensureRepository(repoPath: String, cloneUrl: String) = Unit

    override fun ensureWorktree(repoPath: String, branch: String): String = "$repoPath/$branch"

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        resolvedPaths += selectedPath
        return repositoryWorktreesBySelectedPath.getValue(selectedPath)
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        return repositoryWorktreesBySelectedPath.values.first().worktrees
    }

    override fun removeWorktree(worktreePath: String) = Unit
}

private class RecordingEngHubConfigWriter : EngHubConfigWriter {
    val savedConfigs = MutableStateFlow<List<EngHubConfig>>(emptyList())

    override fun save(config: EngHubConfig) {
        savedConfigs.value += config
    }
}

private class LocalRepositoryNoOpDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private class LocalRepositoryNoOpDesktopLauncher : DesktopLauncher {
    override fun openUrl(url: String) = Unit

    override fun openInIdea(projectPath: String) = Unit
}

private class NoOpNotificationSubscriptionStore : NotificationSubscriptionStore {
    override fun listUnsubscribedThreadIds(): Set<String> = emptySet()

    override fun saveUnsubscribedThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        unsubscribedAtEpochMs: Long,
    ) = Unit
}
