package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.PullRequestRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

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

        viewModel.checkoutExistingBranch(DEV_LAKE_ROOT, pullRequest.branch)
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

        viewModel.checkoutExistingBranch(DEV_LAKE_ROOT, branch)
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
