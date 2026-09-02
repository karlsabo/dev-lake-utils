package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
