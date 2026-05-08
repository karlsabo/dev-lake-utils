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
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.User
import com.github.karlsabo.notifications.NotificationSubscriptionStore
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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

        val repositories = withTimeout(2_000.milliseconds) {
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
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == DEV_LAKE_ROOT && it.worktrees.isNotEmpty() }
            }
        }

        viewModel.addLocalRepository(DOCS_SELECTED_WORKTREE)
        val repositories = withTimeout(2_000.milliseconds) {
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

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), api.resolvedPaths)
        assertEquals("Repository already configured: $DEV_LAKE_ROOT", actionError)
        assertEquals(emptyList(), configWriter.savedConfigs.value)
        assertEquals(initialRepositories, viewModel.localRepositoriesStateFlow.value)
    }

    @Test
    fun expandingConfiguredRepositoryListsWorktreesAndShowsBranchesWithDirtyStatus() = runBlocking {
        val api = RecordingGitWorktreeApi(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    Worktree(
                        path = DEV_LAKE_SELECTED_WORKTREE,
                        branch = "feature/worktree-panel",
                        commitHash = "def456",
                        isDirty = true,
                    ),
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().isExpanded && repositories.single().worktrees.size == 2
            }.single()
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(listOf("main", "feature/worktree-panel"), repository.worktrees.map { it.branch })
        assertEquals(listOf(false, true), repository.worktrees.map { it.isDirty })
    }

    @Test
    fun worktreePollRefreshesAllConfiguredRepositoriesWithoutRefreshingGitHubData() = runBlocking {
        val listCountsByRepo = mutableMapOf<String, Int>()
        val api = RecordingGitWorktreeApi(
            worktreesForRepoPath = { repoPath ->
                val callCount = listCountsByRepo.getOrElse(repoPath) { 0 } + 1
                listCountsByRepo[repoPath] = callCount
                when (repoPath) {
                    DEV_LAKE_ROOT -> if (callCount == 1) {
                        listOf(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"))
                    } else {
                        listOf(
                            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                            Worktree(
                                path = DEV_LAKE_SELECTED_WORKTREE,
                                branch = "feature/worktree-panel",
                                commitHash = "def456",
                                isDirty = true,
                            ),
                        )
                    }

                    DOCS_ROOT -> listOf(Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc"))
                    else -> error("Unexpected repo path $repoPath")
                }
            },
        )
        val gitHubApi = RecordingGitHubApi(emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitHubApi = gitHubApi,
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT, DOCS_ROOT),
            worktreePollIntervalMs = 25,
        )

        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                val devLake = repositories.single { it.path == DEV_LAKE_ROOT }
                val docs = repositories.single { it.path == DOCS_ROOT }
                !devLake.isExpanded &&
                        !docs.isExpanded &&
                        devLake.worktrees.size == 2 &&
                        docs.worktrees.size == 1
            }
        }

        assertEquals(setOf(DEV_LAKE_ROOT, DOCS_ROOT), api.listWorktreeRepoPaths.toSet())
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch })
        assertEquals(listOf(false, true), repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.isDirty })
        assertEquals(listOf("docs-main"), repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch })
        assertEquals(0, gitHubApi.openPullRequestCalls)
        assertEquals(0, gitHubApi.notificationListCalls)
    }

    @Test
    fun concurrentRepositoryExpansionsPreserveBothRepositoryStates() = runBlocking {
        val devLakeListStarted = CompletableDeferred<Unit>()
        val docsListStarted = CompletableDeferred<Unit>()
        val releaseLists = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                ),
                DOCS_ROOT to listOf(
                    Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc"),
                ),
            ),
            onListWorktrees = { repoPath ->
                when (repoPath) {
                    DEV_LAKE_ROOT -> devLakeListStarted.complete(Unit)
                    DOCS_ROOT -> docsListStarted.complete(Unit)
                }
                runBlocking { releaseLists.await() }
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT, DOCS_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        viewModel.toggleLocalRepositoryExpansion(DOCS_ROOT)
        withTimeout(2_000.milliseconds) {
            devLakeListStarted.await()
            docsListStarted.await()
        }

        releaseLists.complete(Unit)
        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.all { it.isExpanded && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(setOf(DEV_LAKE_ROOT, DOCS_ROOT), api.listWorktreeRepoPaths.toSet())
        assertEquals(listOf("main"), repositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch })
        assertEquals(listOf("docs-main"), repositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch })
    }

    @Test
    fun expandingConfiguredRepositoryFailureSetsActionErrorWithoutExpanding() = runBlocking {
        val api = RecordingGitWorktreeApi(
            listWorktreesFailure = IllegalStateException("git worktree list failed"),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals("git worktree list failed", actionError)
        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
        assertEquals(emptyList(), viewModel.localRepositoriesStateFlow.value.single().worktrees)
    }

    @Test
    fun duplicateExpandClicksWhileLoadingDoNotStartStaleExpansionJobs() = runBlocking {
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(
                    Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                ),
            ),
            onListWorktrees = {
                listStarted.complete(Unit)
                runBlocking { releaseList.await() }
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            listStarted.await()
        }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeRepoPaths)

        releaseList.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().isExpanded
            }
        }

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)

        assertEquals(false, viewModel.localRepositoriesStateFlow.value.single().isExpanded)
    }

    @Test
    fun openingExistingWorktreeRunsConfiguredSetupInSelectedWorktreePath() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val api = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap())
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = api,
                configWriter = RecordingEngHubConfigWriter(),
                localRepositories = listOf(repoRoot),
                worktreeSetupCommands = mapOf(repoRoot to listOf("pwd > opened-path.txt")),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath !in it }
            }

            val openedPath = readText(Path(worktreePath, "opened-path.txt")).trim()
            assertTrue(openedPath.endsWith(worktreePath.substringAfterLast('/')))
            assertEquals(emptyList(), api.ensureWorktreeCalls)
            assertEquals(emptyList(), api.ensureRepositoryCalls)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun openingExistingWorktreeTracksProgressForSelectedWorktreeOnly() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositories = listOf(repoRoot),
                worktreeSetupCommands = mapOf(
                    repoRoot to listOf("while [ ! -f release-open ]; do sleep 0.01; done"),
                ),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath in it }
            }
            assertEquals(setOf(worktreePath), inProgress)

            writeText(Path(worktreePath, "release-open"), "")
            val completed = withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath !in it }
            }
            assertEquals(emptySet(), completed)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun concurrentExistingWorktreeCompletionsClearAllProgress() = runBlocking {
        val repoRoot = createTempDir("repo")
        val firstWorktreePath = createTempDir("worktree-first")
        val secondWorktreePath = createTempDir("worktree-second")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositories = listOf(repoRoot),
                worktreeSetupCommands = mapOf(
                    repoRoot to listOf("while [ ! -f release-open ]; do sleep 0.01; done"),
                ),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, firstWorktreePath)
            viewModel.openLocalWorktree(repoRoot, secondWorktreePath)

            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { paths ->
                    firstWorktreePath in paths && secondWorktreePath in paths
                }
            }
            assertEquals(setOf(firstWorktreePath, secondWorktreePath), inProgress)

            writeText(Path(firstWorktreePath, "release-open"), "")
            writeText(Path(secondWorktreePath, "release-open"), "")
            val completed = withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { paths ->
                    firstWorktreePath !in paths && secondWorktreePath !in paths
                }
            }
            assertEquals(emptySet(), completed)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(firstWorktreePath)
            removeTempDir(secondWorktreePath)
        }
    }

    @Test
    fun concurrentDuplicateOpenAttemptsStartOneSetupJob() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        val setupCountPath = Path(repoRoot, "setup-count.txt")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositories = listOf(repoRoot),
                worktreeSetupCommands = mapOf(
                    repoRoot to listOf(
                        "printf x >> '$setupCountPath'",
                        "while [ ! -f release-open ]; do sleep 0.01; done",
                    ),
                ),
                setupShell = "/bin/bash",
            )
            val releaseOpenAttempts = CompletableDeferred<Unit>()
            val openAttempts = List(50) {
                async(Dispatchers.Default) {
                    releaseOpenAttempts.await()
                    viewModel.openLocalWorktree(repoRoot, worktreePath)
                }
            }

            releaseOpenAttempts.complete(Unit)
            openAttempts.awaitAll()
            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath in it }
            }
            assertEquals(setOf(worktreePath), inProgress)

            writeText(Path(worktreePath, "release-open"), "")
            withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath !in it }
            }

            assertEquals("x", readText(setupCountPath))
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun openingExistingWorktreeSetupFailureSetsActionError() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositories = listOf(repoRoot),
                worktreeSetupCommands = mapOf(repoRoot to listOf("echo setup failed >&2", "exit 23")),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val actionError = withTimeout(2_000.milliseconds) {
                viewModel.actionErrorStateFlow.first { it != null }
            }

            assertTrue(actionError!!.contains("Setup commands failed for $worktreePath"))
            assertTrue(actionError.contains("exit code 23"))
            assertTrue(actionError.contains("setup failed"))
            assertEquals(emptySet(), viewModel.openingLocalWorktreePathsStateFlow.value)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }
}

private class RecordingGitHubApi(
    private val pullRequestsByUrl: Map<String, PullRequest>,
) : GitHubApi {
    val pullRequestByUrlCalls = mutableListOf<String>()
    var openPullRequestCalls = 0
        private set
    var notificationListCalls = 0
        private set

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        pullRequestByUrlCalls += url
        return pullRequestsByUrl.getValue(url)
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        openPullRequestCalls += 1
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
        notificationListCalls += 1
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

private fun testIssue(@Suppress("SameParameterValue") issueApiUrl: String, pullApiUrl: String?): Issue {
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
        labels = emptyList(),
        draft = false,
        createdAt = now,
        updatedAt = now,
        closedAt = null,
        pullRequest = pullApiUrl?.let { PullRequest(url = it) },
        comments = 0,
    )
}

private fun createLocalRepositoryViewModel(
    gitHubApi: RecordingGitHubApi = RecordingGitHubApi(emptyMap()),
    gitWorktreeApi: RecordingGitWorktreeApi,
    configWriter: RecordingEngHubConfigWriter,
    localRepositories: List<String> = emptyList(),
    worktreePollIntervalMs: Long = 120_000,
    worktreeSetupCommands: Map<String, List<String>> = emptyMap(),
    setupShell: String = "/bin/zsh",
): EngHubViewModel {
    return EngHubViewModel(
        gitHubApi = gitHubApi,
        gitHubNotificationService = GitHubNotificationService(gitHubApi),
        gitWorktreeApi = gitWorktreeApi,
        desktopLauncher = LocalRepositoryNoOpDesktopLauncher(),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = configWriter,
        config = EngHubConfig(
            pollIntervalMs = 60_000,
            worktreePollIntervalMs = worktreePollIntervalMs,
            localRepositories = localRepositories,
            worktreeSetupCommands = worktreeSetupCommands,
            setupShell = setupShell,
        ),
        notificationSubscriptionStore = NoOpNotificationSubscriptionStore(),
    )
}

private class RecordingGitWorktreeApi(
    private val repositoryWorktreesBySelectedPath: Map<String, RepositoryWorktrees>,
    worktreesByRepoPath: Map<String, List<Worktree>>? = null,
    private val worktreesForRepoPath: ((String) -> List<Worktree>)? = null,
    private val listWorktreesFailure: RuntimeException? = null,
    private val onListWorktrees: (String) -> Unit = {},
) : GitWorktreeApi {
    constructor(
        worktreesForRepoPath: (String) -> List<Worktree>,
    ) : this(
        repositoryWorktreesBySelectedPath = emptyMap(),
        worktreesForRepoPath = worktreesForRepoPath,
    )

    constructor(
        worktreesByRepoPath: Map<String, List<Worktree>>,
        onListWorktrees: (String) -> Unit = {},
    ) : this(
        repositoryWorktreesBySelectedPath = emptyMap(),
        worktreesByRepoPath = worktreesByRepoPath,
        onListWorktrees = onListWorktrees,
    )

    constructor(
        listWorktreesFailure: RuntimeException,
    ) : this(
        repositoryWorktreesBySelectedPath = emptyMap(),
        listWorktreesFailure = listWorktreesFailure,
    )

    constructor(repositoryWorktrees: RepositoryWorktrees) : this(
        mapOf(repositoryWorktrees.selectedWorktreePath to repositoryWorktrees),
    )

    val resolvedPaths = mutableListOf<String>()
    val listWorktreeRepoPaths = mutableListOf<String>()
    val ensureRepositoryCalls = mutableListOf<Pair<String, String>>()
    val ensureWorktreeCalls = mutableListOf<Pair<String, String>>()
    private val worktreesByRepoPath = worktreesByRepoPath
        ?: repositoryWorktreesBySelectedPath.values.associate { it.rootPath to it.worktrees }

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        ensureRepositoryCalls += repoPath to cloneUrl
    }

    override fun ensureWorktree(repoPath: String, branch: String): String {
        ensureWorktreeCalls += repoPath to branch
        return "$repoPath/$branch"
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        resolvedPaths += selectedPath
        return repositoryWorktreesBySelectedPath.getValue(selectedPath)
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        listWorktreeRepoPaths += repoPath
        onListWorktrees(repoPath)
        listWorktreesFailure?.let { throw it }
        return worktreesForRepoPath?.invoke(repoPath) ?: worktreesByRepoPath.getValue(repoPath)
    }

    override fun removeWorktree(worktreePath: String) = Unit
}

private fun createTempDir(prefix: String): String {
    val dirName = "$prefix-${Random.nextLong().toULong().toString(16)}"
    val path = Path(SystemTemporaryDirectory, dirName)
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

private fun removeTempDir(path: String) {
    val root = Path(path)
    if (!SystemFileSystem.exists(root)) return
    deleteRecursively(root)
}

private fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}

private fun readText(path: Path): String {
    return SystemFileSystem.source(path).buffered().use { it.readString() }
}

private fun writeText(path: Path, @Suppress("SameParameterValue") text: String) {
    SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
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
