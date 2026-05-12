package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
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
private const val EXAMPLE_WEB_ROOT = "/workspace/example-web"
private const val NEW_LOCAL_REPO_ROOT = "/workspace/new-local-repo"

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
        assertEquals(
            listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
            configWriter.savedConfigs.value.single().localRepositories,
        )
        assertEquals(listOf("dev-lake-utils"), repositories.map { it.name })
        assertEquals(listOf("main", "feature/worktree-panel"), repositories.single().worktrees.map { it.branch })
        assertEquals(listOf(true, false), repositories.single().worktrees.map { it.isRoot })
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
        assertEquals(
            listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT),
                LocalRepositoryConfig(path = DOCS_ROOT),
            ),
            configWriter.savedConfigs.value.last().localRepositories,
        )
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
    fun addingLocalRepositoryPersistsUnifiedEntryWithoutChangingExistingSetupCommands() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktrees = RepositoryWorktrees(
                rootPath = NEW_LOCAL_REPO_ROOT,
                selectedWorktreePath = NEW_LOCAL_REPO_ROOT,
                worktrees = listOf(
                    Worktree(path = NEW_LOCAL_REPO_ROOT, branch = "main", commitHash = "abc123"),
                ),
            ),
        )
        val configWriter = RecordingEngHubConfigWriter()
        val existingRepository = LocalRepositoryConfig(
            path = EXAMPLE_WEB_ROOT,
            setupCommands = listOf("direnv allow", "direnv exec . idea ./"),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = configWriter,
            localRepositoryConfigs = listOf(existingRepository),
        )

        viewModel.addLocalRepository(NEW_LOCAL_REPO_ROOT)

        val repositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.any { it.path == NEW_LOCAL_REPO_ROOT && it.worktrees.isNotEmpty() }
            }
        }
        val savedConfig = configWriter.savedConfigs.value.single()

        assertEquals(
            listOf(
                existingRepository,
                LocalRepositoryConfig(path = NEW_LOCAL_REPO_ROOT, setupCommands = emptyList()),
            ),
            savedConfig.localRepositories,
        )
        assertEquals(emptyMap(), savedConfig.worktreeSetupCommands)
        assertEquals(
            listOf("example-web", "new-local-repo"),
            repositories.map { it.name },
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
    fun rendersConfiguredLocalRepositoryObjectsInFolderNameOrder() {
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(
                    path = "/workspace/example-service",
                    setupCommands = listOf("direnv allow"),
                ),
                LocalRepositoryConfig(
                    path = "/workspace/example-web",
                    setupCommands = listOf("direnv exec . idea ./"),
                ),
                LocalRepositoryConfig(path = "/workspace/example-worker"),
                LocalRepositoryConfig(path = "/workspace/example-infra"),
            ),
        )

        val repositories = viewModel.localRepositoriesStateFlow.value

        assertEquals(
            listOf("example-infra", "example-service", "example-web", "example-worker"),
            repositories.map { it.name },
        )
        assertEquals(
            listOf(
                "/workspace/example-infra",
                "/workspace/example-service",
                "/workspace/example-web",
                "/workspace/example-worker",
            ),
            repositories.map { it.path },
        )
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
        assertEquals(listOf(true, false), repository.worktrees.map { it.isRoot })
    }

    @Test
    fun worktreePollRefreshesUnifiedRepositoryEntriesWithoutRefreshingGitHubData() = runBlocking {
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
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(
                    path = DEV_LAKE_ROOT,
                    setupCommands = listOf("direnv allow"),
                ),
                LocalRepositoryConfig(
                    path = DOCS_ROOT,
                    setupCommands = listOf("direnv exec . idea ./"),
                ),
            ),
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
    fun openingExistingWorktreeRunsUnifiedRepositorySetupCommands() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(
                        path = "$repoRoot/",
                        setupCommands = listOf("pwd > unified-opened-path.txt"),
                    ),
                ),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath !in it }
            }

            val openedPath = readText(Path(worktreePath, "unified-opened-path.txt")).trim()
            assertTrue(openedPath.endsWith(worktreePath.substringAfterLast('/')))
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun openingExistingWorktreeFallsBackToLegacySetupWhenUnifiedRepositoryEntryIsEmpty() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(worktreesByRepoPath = emptyMap()),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(LocalRepositoryConfig(path = "$repoRoot/")),
                worktreeSetupCommands = mapOf(repoRoot to listOf("pwd > legacy-opened-path.txt")),
                setupShell = "/bin/bash",
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            withTimeout(2_000.milliseconds) {
                viewModel.openingLocalWorktreePathsStateFlow.first { worktreePath !in it }
            }

            val openedPath = readText(Path(worktreePath, "legacy-opened-path.txt")).trim()
            assertTrue(openedPath.endsWith(worktreePath.substringAfterLast('/')))
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

    @Test
    fun archivingCleanNonRootWorktreeArchivesAndRefreshesRepository() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesForRepoPath = { currentWorktrees },
            onArchiveWorktree = { repoPath, worktreePath, _ ->
                assertEquals(DEV_LAKE_ROOT, repoPath)
                assertEquals(DEV_LAKE_SELECTED_WORKTREE, worktreePath)
                currentWorktrees = listOf(rootWorktree)
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun dirtyArchiveFailurePromptsForForceArchiveThenForceArchivesAndRefreshesRepository() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
            isDirty = true,
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesForRepoPath = { currentWorktrees },
            onArchiveWorktree = { _, _, force ->
                if (!force) throw IllegalStateException("fatal: contains modified files")
                currentWorktrees = listOf(rootWorktree)
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }
        assertEquals(DEV_LAKE_ROOT, forceRequest.repoRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, forceRequest.worktreePath)
        assertEquals(null, viewModel.actionErrorStateFlow.value)

        viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(
            listOf(
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
            ),
            api.archiveWorktreeCalls,
        )
        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun cleanupFailureForDirtyPathDoesNotPromptForForceArchive() = runBlocking {
        val dirtyPath = "$DEV_LAKE_ROOT-dirty-cleanup"
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = dirtyPath,
            branch = "feature/dirty-cleanup",
            commitHash = "def456",
        )
        val worktrees = listOf(rootWorktree, featureWorktree)
        val failureMessage = "Failed to delete leftover worktree directory at $dirtyPath"
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
            onArchiveWorktree = { _, _, _ ->
                throw IllegalStateException(failureMessage)
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, dirtyPath)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(failureMessage, actionError)
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(listOf(DEV_LAKE_ROOT to dirtyPath), api.archiveWorktreeCalls)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun forceArchiveConfirmationStartsWhileDirtyFailureRefreshIsStillRunning() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
            isDirty = true,
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        var archiveAttempts = 0
        val dirtyFailureRefreshStarted = CompletableDeferred<Unit>()
        val releaseDirtyFailureRefresh = CompletableDeferred<Unit>()
        val forceArchiveStarted = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesForRepoPath = { currentWorktrees },
            onListWorktrees = {
                if (archiveAttempts == 1) {
                    dirtyFailureRefreshStarted.complete(Unit)
                    runBlocking { releaseDirtyFailureRefresh.await() }
                }
            },
            onArchiveWorktree = { _, _, force ->
                archiveAttempts += 1
                if (!force) throw IllegalStateException("fatal: contains modified files")
                forceArchiveStarted.complete(Unit)
                currentWorktrees = listOf(rootWorktree)
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }
        withTimeout(2_000.milliseconds) {
            dirtyFailureRefreshStarted.await()
        }

        try {
            viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

            withTimeout(2_000.milliseconds) {
                forceArchiveStarted.await()
            }
        } finally {
            releaseDirtyFailureRefresh.complete(Unit)
        }
        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun forceArchiveFailureSetsActionErrorAndLeavesRowsVisible() = runBlocking {
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(
                path = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/worktree-panel",
                commitHash = "def456",
                isDirty = true,
            ),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
            onArchiveWorktree = { _, _, force ->
                if (force) throw IllegalStateException("force archive failed")
                throw IllegalStateException("fatal: contains modified files")
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }

        viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("force archive failed", actionError)
        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun archivingWorktreeFailureSetsActionErrorWithoutRefreshingRows() = runBlocking {
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(
                path = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/worktree-panel",
                commitHash = "def456",
            ),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
            archiveWorktreeFailure = IllegalStateException("archive failed"),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("archive failed", actionError)
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun archivingPostRemoveFailureRefreshesRepositoryRows() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            worktreesForRepoPath = { currentWorktrees },
            onArchiveWorktree = { _, _, _ ->
                currentWorktrees = listOf(rootWorktree)
                throw IllegalStateException("cleanup failed")
            },
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositories = listOf(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("cleanup failed", actionError)
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
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
    localRepositoryConfigs: List<LocalRepositoryConfig> =
        localRepositories.map { LocalRepositoryConfig(path = it) },
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
            localRepositories = localRepositoryConfigs,
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
    private val archiveWorktreeFailure: RuntimeException? = null,
    private val onArchiveWorktree: (String, String, Boolean) -> Unit = { _, _, _ -> },
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
    val archiveWorktreeCalls = mutableListOf<Pair<String, String>>()
    val archiveWorktreeForceValues = mutableListOf<Boolean>()
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

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean) {
        archiveWorktreeCalls += repoPath to worktreePath
        archiveWorktreeForceValues += force
        archiveWorktreeFailure?.let { throw it }
        onArchiveWorktree(repoPath, worktreePath, force)
    }
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
