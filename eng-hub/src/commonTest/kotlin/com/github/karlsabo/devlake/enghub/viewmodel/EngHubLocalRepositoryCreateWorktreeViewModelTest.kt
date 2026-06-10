package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalRepositoryCreateWorktreeViewModelTest {

    @Test
    fun requestCreateLocalWorktreeFromRepositoryResolvesBaseFromDefaultBranchInference() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                defaultBranchRefsByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val request = withTimeout(2_000.milliseconds) {
            viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals(
            CreateLocalWorktreeFromRepositoryRequest(
                repoRootPath = DEV_LAKE_ROOT,
                baseWorktreePath = DEV_LAKE_ROOT,
                baseBranch = "main",
            ),
            request,
        )
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }

    @Test
    fun createLocalWorktreeFromRepositoryDefaultBranchCreatesSetupAndRefreshes() = runBlocking {
        val targetBranch = "feature/new-dashboard"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "def456")
        var currentWorktrees = listOf(rootWorktree)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                defaultBranchRefsByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { _, _, _, _ ->
                    currentWorktrees = currentWorktrees + targetWorktree
                    targetWorktreePath.value
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup repo-row worktree"),
        )
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }
        }

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)
        val defaultBase = withTimeout(2_000.milliseconds) {
            viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.first { it != null }
        }
        viewModel.createLocalWorktreeFromBase(
            repoRootPath = defaultBase!!.repoRootPath,
            baseWorktreePath = defaultBase.baseWorktreePath,
            baseBranch = defaultBase.baseBranch,
            targetBranch = targetBranch,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        assertEquals(
            listOf(
                CreateBranchWorktreeCall(
                    repoPath = DEV_LAKE_ROOT,
                    baseWorktreePath = DEV_LAKE_ROOT,
                    baseBranch = "main",
                    targetBranch = targetBranch,
                ),
            ),
            api.createBranchWorktreeCalls,
        )
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            viewModel.setupStatusesStateFlow.value[targetWorktreePath],
        )
        assertEquals(
            listOf("setup repo-row worktree"),
            setupRunner.requestFor(targetWorktreePath)?.setupCommands,
        )

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }
        assertEquals(listOf("main", targetBranch), repository.worktrees.map { it.branch })

        setupRunner.complete(targetWorktreePath)
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun requestCreateLocalWorktreeFromRepositoryReportsActionErrorWhenDefaultBranchIsAbsent() = runBlocking {
        val api = RecordingGitWorktreeApi(repositoryWorktreesBySelectedPath = emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals("Could not infer default branch for $DEV_LAKE_ROOT", actionError?.message)
        assertNull(viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.value)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }

    @Test
    fun requestCreateLocalWorktreeFromRepositoryReportsActionErrorWhenDefaultBranchInferenceThrows() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                inferDefaultBranchRefFailure = IllegalStateException("default branch lookup failed"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals("default branch lookup failed", actionError?.message)
        assertNull(viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.value)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }
}
