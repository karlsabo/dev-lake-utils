package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.PullRequestRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private const val ENGINEERING_DOCS_ROOT = "/repos/engineering-docs"

class EngHubExistingBranchWorktreeViewModelTest {
    @Test
    fun discoverExistingBranchesPublishesRepositoryScopedResults() = runBlocking {
        val branch = "feature/existing-worktree"
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(DEV_LAKE_ROOT, "main", "abc123")),
                ),
                existingBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf("main", branch)),
                originBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf("main", branch)),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)

        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first { !it.isLoading && branch in it.branches }
        }
        assertEquals(DEV_LAKE_ROOT, discovery.repoRootPath)
        assertEquals(listOf("main", branch), discovery.branches)
        assertEquals(listOf("main", branch), discovery.originBranches)
        assertEquals(true, discovery.originBranchRefreshSucceeded)
    }

    @Test
    fun staleDiscoveryCannotReplaceResultsForTheActiveRepository() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstReturned = CompletableDeferred<Unit>()
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                existingBranchesForRepoPath = { repoPath ->
                    when (repoPath) {
                        DEV_LAKE_ROOT -> {
                            firstStarted.complete(Unit)
                            runBlocking { releaseFirst.await() }
                            firstReturned.complete(Unit)
                            listOf("feature/stale")
                        }

                        DOCS_ROOT -> listOf("feature/current")

                        else -> error("Unexpected repository $repoPath")
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)
        withTimeout(5.seconds) { firstStarted.await() }
        viewModel.discoverExistingBranches(DOCS_ROOT)
        withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first {
                it.repoRootPath == DOCS_ROOT && !it.isLoading
            }
        }

        releaseFirst.complete(Unit)
        withTimeout(5.seconds) { firstReturned.await() }
        delay(100)

        assertEquals(DOCS_ROOT, viewModel.existingBranchDiscoveryStateFlow.value.repoRootPath)
        assertEquals(listOf("feature/current"), viewModel.existingBranchDiscoveryStateFlow.value.branches)
    }

    @Test
    fun globalBranchDiscoverySearchesConfiguredRepositoriesInParallel() = runBlocking {
        val docBranch = "feature/doc-search"
        val devLakeStarted = CompletableDeferred<Unit>()
        val engineeringDocsStarted = CompletableDeferred<Unit>()
        val releaseDevLake = CompletableDeferred<Unit>()
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                existingBranchesForRepoPath = { repoPath ->
                    when (repoPath) {
                        DEV_LAKE_ROOT -> {
                            devLakeStarted.complete(Unit)
                            runBlocking { releaseDevLake.await() }
                            listOf("main", "feature/other")
                        }

                        ENGINEERING_DOCS_ROOT -> {
                            engineeringDocsStarted.complete(Unit)
                            listOf("main", docBranch)
                        }

                        else -> error("Unexpected repository $repoPath")
                    }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT),
                LocalRepositoryConfig(path = ENGINEERING_DOCS_ROOT),
            ),
        )

        viewModel.discoverGlobalExistingBranches()
        withTimeout(5.seconds) { devLakeStarted.await() }
        withTimeout(5.seconds) { engineeringDocsStarted.await() }
        releaseDevLake.complete(Unit)

        val discovery = withTimeout(5.seconds) {
            viewModel.globalExistingBranchDiscoveryStateFlow.first { !it.isLoading }
        }
        assertEquals(listOf(DEV_LAKE_ROOT, ENGINEERING_DOCS_ROOT), discovery.repoRootPaths)
        assertEquals(listOf("main", docBranch), discovery.repositories.getValue(ENGINEERING_DOCS_ROOT).branches)
    }

    @Test
    fun globalPullRequestDiscoveryPublishesCandidateDuringBranchLoading() = runBlocking {
        val branch = "feature/pr-search"
        val devLakeStarted = CompletableDeferred<Unit>()
        val releaseDevLake = CompletableDeferred<Unit>()
        val git = globalPullRequestGit(
            branch = branch,
            devLakeStarted = devLakeStarted,
            releaseDevLake = releaseDevLake,
        )
        val gitHub = engineeringDocsPullRequestApi(branch)
        val viewModel = globalPullRequestViewModel(git, gitHub)

        viewModel.discoverGlobalExistingBranches()
        withTimeout(5.seconds) { devLakeStarted.await() }
        viewModel.discoverGlobalExistingPullRequests("456")

        val discovery = withTimeout(5.seconds) {
            viewModel.globalExistingBranchDiscoveryStateFlow.first {
                it.repositories[ENGINEERING_DOCS_ROOT]?.pullRequest?.branch == branch
            }
        }
        val pullRequest = discovery.repositories.getValue(ENGINEERING_DOCS_ROOT).pullRequest
        assertEquals(true, discovery.isLoading)
        assertEquals(true, discovery.repositories.getValue(DEV_LAKE_ROOT).isLoading)
        assertEquals("owner/engineering-docs", pullRequest?.repositoryFullName)
        assertEquals(456, pullRequest?.number)
        assertEquals(branch, pullRequest?.branch)
        assertEquals(
            listOf("https://api.github.com/repos/owner/engineering-docs/pulls/456"),
            gitHub.pullRequestByUrlCalls,
        )

        releaseDevLake.complete(Unit)
        Unit
    }

    @Test
    fun globalPullRequestDiscoveryFiltersForksWithoutDiscardingBaseRepositoryResults() = runBlocking {
        val branch = "feature/pr-search"
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                originUrlsByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "git@github.com:owner/dev-lake-utils.git",
                    ENGINEERING_DOCS_ROOT to "git@github.com:owner/engineering-docs.git",
                ),
            ),
        )
        val gitHub = RecordingGitHubApi(
            pullRequestsByUrl = mapOf(
                "https://api.github.com/repos/owner/dev-lake-utils/pulls/456" to PullRequest(
                    number = 456,
                    head = PullRequestHead(
                        ref = "feature/fork",
                        repo = PullRequestRepository("contributor/dev-lake-utils"),
                    ),
                ),
                "https://api.github.com/repos/owner/engineering-docs/pulls/456" to PullRequest(
                    number = 456,
                    head = PullRequestHead(
                        ref = branch,
                        repo = PullRequestRepository("owner/engineering-docs"),
                    ),
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT),
                LocalRepositoryConfig(path = ENGINEERING_DOCS_ROOT),
            ),
            services = LocalRepositoryViewModelServices(gitHubApi = gitHub),
        )

        viewModel.discoverGlobalExistingPullRequests("456")

        val discovery = withTimeout(5.seconds) {
            viewModel.globalExistingBranchDiscoveryStateFlow.first { state ->
                state.repositories.size == 2 && state.repositories.values.none { it.isPullRequestLoading }
            }
        }
        val devLake = discovery.repositories.getValue(DEV_LAKE_ROOT)
        val engineeringDocs = discovery.repositories.getValue(ENGINEERING_DOCS_ROOT)
        assertNull(devLake.pullRequest)
        assertEquals("Fork pull requests are not supported.", devLake.unsupportedPullRequestMessage)
        assertEquals(branch, engineeringDocs.pullRequest?.branch)
    }

    @Test
    fun globalBranchCheckoutRunsSelectedRepositorySetupCommands() = runBlocking {
        val branch = "feature/doc-search"
        val worktreePath = buildWorktreePath(ENGINEERING_DOCS_ROOT, branch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(DEV_LAKE_ROOT, "main", "abc123")),
                    ENGINEERING_DOCS_ROOT to listOf(Worktree(ENGINEERING_DOCS_ROOT, "main", "def456")),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCheckoutExistingBranchWorktree = { call ->
                    assertEquals(CheckoutExistingBranchWorktreeCall(ENGINEERING_DOCS_ROOT, branch), call)
                    worktreePath.value
                },
            ),
        )
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("./dev/setup")),
                LocalRepositoryConfig(path = ENGINEERING_DOCS_ROOT, setupCommands = listOf("./docs/setup")),
            ),
            services = LocalRepositoryViewModelServices(worktreeSetupCoordinator = coordinator),
        )

        viewModel.checkoutExistingBranch(ENGINEERING_DOCS_ROOT, branch, null)
        withTimeout(5.seconds) { setupRunner.awaitStarted(worktreePath) }

        assertEquals(
            listOf(CheckoutExistingBranchWorktreeCall(ENGINEERING_DOCS_ROOT, branch)),
            git.checkoutExistingBranchWorktreeCalls,
        )
        assertEquals(listOf("./docs/setup"), setupRunner.requestFor(worktreePath)?.setupCommands)
        setupRunner.complete(worktreePath)
    }

    @Test
    fun globalRefreshKeepsDiscoveredWorktreePathAvailableForUseExistingWhileLoading() = runBlocking {
        val branch = "feature/already-local"
        val existingWorktreePath = "/tmp/dev-lake-utils-already-local"
        val worktreeKey = WorktreePath(existingWorktreePath)
        val blockedRefresh = BlockedSecondExistingBranchRefresh(branch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val git = alreadyLocalBranchGit(
            branch = branch,
            existingWorktreePath = existingWorktreePath,
            existingBranchesForRepoPath = blockedRefresh::branchesForRepoPath,
        )
        val viewModel = globalUseExistingViewModel(git, setupRunner, this)

        viewModel.discoverGlobalExistingBranches()
        withTimeout(5.seconds) {
            viewModel.globalExistingBranchDiscoveryStateFlow.first { discovery ->
                val repository = discovery.repositories[DEV_LAKE_ROOT]
                !discovery.isLoading && repository?.worktreePathsByBranch?.get(branch) == existingWorktreePath
            }
        }
        viewModel.discoverGlobalExistingBranches()
        withTimeout(5.seconds) { blockedRefresh.awaitSecondRefreshStarted() }

        val loadingRepository = viewModel.globalExistingBranchDiscoveryStateFlow.value
            .repositories.getValue(DEV_LAKE_ROOT)
        assertEquals(true, loadingRepository.isLoading)
        assertEquals(listOf("main", branch), loadingRepository.branches)
        viewModel.checkoutExistingBranch(
            DEV_LAKE_ROOT,
            branch,
            loadingRepository.worktreePathsByBranch.getValue(branch),
        )
        withTimeout(5.seconds) { setupRunner.awaitStarted(worktreeKey) }

        assertEquals(
            listOf(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch)),
            git.checkoutExistingBranchWorktreeCalls,
        )
        assertEquals(emptyList(), git.createBranchWorktreeCalls)
        assertEquals(branch, setupRunner.requestFor(worktreeKey)?.existingBranch)

        blockedRefresh.releaseSecondRefresh()
        setupRunner.complete(worktreeKey)
    }

    @Test
    fun repositoryPullRequestDiscoveryResolvesBaseRepositoryHeadAndRunsSetup() = runBlocking {
        val branch = "feature/pr-worktree"
        val worktreePath = buildWorktreePath(DEV_LAKE_ROOT, branch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val git = pullRequestGit(branch, checkoutPath = worktreePath.value)
        val gitHub = pullRequestApi(branch, headRepository = "owner/dev-lake-utils")
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("./gradlew setup")),
            ),
            services = LocalRepositoryViewModelServices(
                gitHubApi = gitHub,
                worktreeSetupCoordinator = coordinator,
            ),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)
        viewModel.discoverExistingPullRequest(DEV_LAKE_ROOT, "123")
        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first {
                !it.isLoading && !it.isPullRequestLoading && it.pullRequest != null
            }
        }
        val pullRequest = requireNotNull(discovery.pullRequest)
        assertEquals("owner/dev-lake-utils", pullRequest.repositoryFullName)
        assertEquals(123, pullRequest.number)
        assertEquals(branch, pullRequest.branch)

        viewModel.checkoutExistingBranch(DEV_LAKE_ROOT, pullRequest.branch, null)
        withTimeout(5.seconds) { setupRunner.awaitStarted(worktreePath) }

        assertEquals(
            listOf(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch)),
            git.checkoutExistingBranchWorktreeCalls,
        )
        assertEquals(listOf("./gradlew setup"), setupRunner.requestFor(worktreePath)?.setupCommands)
        setupRunner.complete(worktreePath)
    }

    @Test
    fun branchDiscoveryFailureDoesNotDiscardSuccessfulPullRequestDiscovery() = runBlocking {
        val branch = "feature/pr-worktree"
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                existingBranchDiscoveryFailure = IllegalStateException("branch discovery failed"),
                originUrlsByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "git@github.com:owner/dev-lake-utils.git",
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
            services = LocalRepositoryViewModelServices(
                gitHubApi = pullRequestApi(branch, headRepository = "owner/dev-lake-utils"),
            ),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)
        viewModel.discoverExistingPullRequest(DEV_LAKE_ROOT, "123")

        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first {
                !it.isLoading && !it.isPullRequestLoading && it.pullRequest != null
            }
        }
        assertEquals(false, discovery.originBranchRefreshSucceeded)
        assertEquals(branch, discovery.pullRequest?.branch)
    }

    @Test
    fun forkPullRequestIsNotSelectableAndLeavesBranchResultsAvailable() = runBlocking {
        val git = pullRequestGit("feature/fork")
        val gitHub = pullRequestApi("feature/fork", headRepository = "contributor/dev-lake-utils")
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT)),
            services = LocalRepositoryViewModelServices(gitHubApi = gitHub),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)
        viewModel.discoverExistingPullRequest(DEV_LAKE_ROOT, "123")

        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first {
                !it.isLoading && !it.isPullRequestLoading && it.unsupportedPullRequestMessage != null
            }
        }
        assertNull(discovery.pullRequest)
        assertEquals("Fork pull requests are not supported.", discovery.unsupportedPullRequestMessage)
        assertEquals(listOf("main", "feature/fork"), discovery.branches)
    }

    @Test
    fun checkoutExistingSearchResultReusesDiscoveredWorktreeAndRunsSetupWithoutWarning() = runBlocking {
        val branch = "feature/already-local"
        val existingWorktreePath = "/tmp/dev-lake-utils-already-local"
        val worktreeKey = WorktreePath(existingWorktreePath)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(DEV_LAKE_ROOT, "main", "abc123"),
                        Worktree(existingWorktreePath, branch, "def456"),
                    ),
                ),
                existingBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf("main", branch)),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCheckoutExistingBranchWorktree = { call ->
                    assertEquals(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch), call)
                    existingWorktreePath
                },
            ),
        )
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("./gradlew setup")),
            ),
            services = LocalRepositoryViewModelServices(worktreeSetupCoordinator = coordinator),
        )

        viewModel.discoverExistingBranches(DEV_LAKE_ROOT)
        val discovery = withTimeout(5.seconds) {
            viewModel.existingBranchDiscoveryStateFlow.first { !it.isLoading && branch in it.branches }
        }
        viewModel.checkoutExistingBranch(
            DEV_LAKE_ROOT,
            branch,
            discovery.worktreePathsByBranch.getValue(branch),
        )
        withTimeout(5.seconds) { setupRunner.awaitStarted(worktreeKey) }

        assertEquals(mapOf(branch to existingWorktreePath, "main" to DEV_LAKE_ROOT), discovery.worktreePathsByBranch)
        assertEquals(
            listOf(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch)),
            git.checkoutExistingBranchWorktreeCalls,
        )
        assertEquals(emptyList(), git.createBranchWorktreeCalls)
        assertNull(viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.value)
        val setupRequest = requireNotNull(setupRunner.requestFor(worktreeKey))
        assertEquals(branch, setupRequest.existingBranch)
        assertEquals(listOf("./gradlew setup"), setupRequest.setupCommands)

        setupRunner.complete(worktreeKey)
    }

    @Test
    fun checkoutExistingBranchCreatesWorktreeAndRunsConfiguredSetup() = runBlocking {
        val branch = "feature/existing-worktree"
        val worktreePath = buildWorktreePath(DEV_LAKE_ROOT, branch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(DEV_LAKE_ROOT, "main", "abc123")),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCheckoutExistingBranchWorktree = { call ->
                    assertEquals(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch), call)
                    worktreePath.value
                },
            ),
        )
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("./gradlew setup")),
            ),
            services = LocalRepositoryViewModelServices(worktreeSetupCoordinator = coordinator),
        )

        viewModel.checkoutExistingBranch(DEV_LAKE_ROOT, branch, null)
        withTimeout(5.seconds) { setupRunner.awaitStarted(worktreePath) }

        assertEquals(
            listOf(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch)),
            git.checkoutExistingBranchWorktreeCalls,
        )
        val setupRequest = requireNotNull(setupRunner.requestFor(worktreePath))
        assertEquals(branch, setupRequest.existingBranch)
        assertEquals(listOf("./gradlew setup"), setupRequest.setupCommands)
        assertFalse(coordinator.statuses.value.isEmpty())

        setupRunner.complete(worktreePath)
    }

    private fun alreadyLocalBranchGit(
        branch: String,
        existingWorktreePath: String,
        existingBranchesForRepoPath: (String) -> List<String>,
    ) = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(
                    Worktree(DEV_LAKE_ROOT, "main", "abc123"),
                    Worktree(existingWorktreePath, branch, "def456"),
                ),
            ),
            existingBranchesForRepoPath = existingBranchesForRepoPath,
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onCheckoutExistingBranchWorktree = { call ->
                assertEquals(CheckoutExistingBranchWorktreeCall(DEV_LAKE_ROOT, branch), call)
                existingWorktreePath
            },
        ),
    )

    private fun globalUseExistingViewModel(
        git: RecordingGitWorktreeApi,
        setupRunner: BlockingCoordinatorSetupRunner,
        scope: CoroutineScope,
    ) = createLocalRepositoryViewModel(
        gitWorktreeApi = git,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = listOf(
            LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = listOf("./gradlew setup")),
        ),
        services = LocalRepositoryViewModelServices(
            worktreeSetupCoordinator = WorktreeSetupCoordinator(
                gitWorktreeApi = git,
                setupCommandRunner = setupRunner,
                scope = scope,
            ),
        ),
    )

    private fun globalPullRequestGit(
        branch: String,
        devLakeStarted: CompletableDeferred<Unit>,
        releaseDevLake: CompletableDeferred<Unit>,
    ) = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            existingBranchesForRepoPath = { repoPath ->
                when (repoPath) {
                    DEV_LAKE_ROOT -> {
                        devLakeStarted.complete(Unit)
                        runBlocking { releaseDevLake.await() }
                        listOf("main")
                    }

                    ENGINEERING_DOCS_ROOT -> listOf("main", branch)

                    else -> error("Unexpected repository $repoPath")
                }
            },
            originBranchesByRepoPath = mapOf(ENGINEERING_DOCS_ROOT to listOf(branch)),
            originUrlsByRepoPath = mapOf(
                ENGINEERING_DOCS_ROOT to "https://github.com/owner/engineering-docs.git",
            ),
        ),
    )

    private fun engineeringDocsPullRequestApi(branch: String) = RecordingGitHubApi(
        pullRequestsByUrl = mapOf(
            "https://api.github.com/repos/owner/engineering-docs/pulls/456" to PullRequest(
                number = 456,
                state = "closed",
                head = PullRequestHead(
                    ref = branch,
                    repo = PullRequestRepository("owner/engineering-docs"),
                ),
            ),
        ),
    )

    private fun globalPullRequestViewModel(
        git: RecordingGitWorktreeApi,
        gitHub: RecordingGitHubApi,
    ) = createLocalRepositoryViewModel(
        gitWorktreeApi = git,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = listOf(
            LocalRepositoryConfig(path = DEV_LAKE_ROOT),
            LocalRepositoryConfig(path = ENGINEERING_DOCS_ROOT),
        ),
        services = LocalRepositoryViewModelServices(gitHubApi = gitHub),
    )

    private fun pullRequestGit(branch: String, checkoutPath: String? = null) = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(Worktree(DEV_LAKE_ROOT, "main", "abc123")),
            ),
            existingBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf("main", branch)),
            originBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf("main", branch)),
            originUrlsByRepoPath = mapOf(
                DEV_LAKE_ROOT to "git@github.com:owner/dev-lake-utils.git",
            ),
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onCheckoutExistingBranchWorktree = { checkoutPath ?: error("Unexpected checkout") },
        ),
    )

    private fun pullRequestApi(branch: String, headRepository: String) = RecordingGitHubApi(
        pullRequestsByUrl = mapOf(
            "https://api.github.com/repos/owner/dev-lake-utils/pulls/123" to PullRequest(
                number = 123,
                state = "closed",
                head = PullRequestHead(
                    ref = branch,
                    repo = PullRequestRepository(headRepository),
                ),
            ),
        ),
    )
}

private class BlockedSecondExistingBranchRefresh(
    private val branch: String,
) {
    private val secondRefreshStarted = CompletableDeferred<Unit>()
    private val releaseSecondRefreshSignal = CompletableDeferred<Unit>()
    private var refreshCount = 0

    fun branchesForRepoPath(repoPath: String): List<String> {
        assertEquals(DEV_LAKE_ROOT, repoPath)
        refreshCount += 1
        if (refreshCount == 1) return listOf("main", branch)

        secondRefreshStarted.complete(Unit)
        runBlocking { releaseSecondRefreshSignal.await() }
        return listOf("main", branch)
    }

    suspend fun awaitSecondRefreshStarted() {
        secondRefreshStarted.await()
    }

    fun releaseSecondRefresh() {
        releaseSecondRefreshSignal.complete(Unit)
    }
}
