package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCommandResult
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeCreateHappyPathViewModelTest {

    @Test
    fun createLocalWorktreeFromBaseRecordsSubmittedDialogRequestAtViewModelBoundary() {
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            ),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr",
            baseBranch = "feature/base-pr",
            targetBranch = "feature/stacked-pr",
        )

        assertEquals(
            CreateLocalWorktreeFromBaseRequest(
                repoRootPath = DEV_LAKE_ROOT,
                baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr",
                baseBranch = "feature/base-pr",
                targetBranch = "feature/stacked-pr",
            ),
            viewModel.lastCreateLocalWorktreeFromBaseRequestStateFlow.value,
        )
    }

    @Test
    fun createLocalWorktreeFromBaseCreatesDerivedWorktreeAndStartsConfiguredSetup() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { request -> buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup stacked worktree"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        assertEquals(
            listOf(
                CreateBranchWorktreeCall(
                    repoPath = DEV_LAKE_ROOT,
                    baseWorktreePath = baseWorktreePath,
                    baseBranch = "feature/base-pr",
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
            listOf("setup stacked worktree"),
            setupRunner.requestFor(targetWorktreePath)?.setupCommands,
        )

        setupRunner.complete(targetWorktreePath)
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromDetachedBaseCreatesFromCommitIshAndStartsConfiguredSetup() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-detached"
        val baseCommitIsh = "abc123"
        val targetBranch = "feature/from-detached"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktreeFromCommitIsh = { request ->
                    buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup detached-based worktree"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "(detached)",
            targetBranch = targetBranch,
            baseCommitIsh = baseCommitIsh,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        assertEquals(
            listOf(
                CreateBranchWorktreeFromCommitIshCall(
                    repoPath = DEV_LAKE_ROOT,
                    baseWorktreePath = baseWorktreePath,
                    baseCommitIsh = baseCommitIsh,
                    targetBranch = targetBranch,
                ),
            ),
            api.createBranchWorktreeFromCommitIshCalls,
        )
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            viewModel.setupStatusesStateFlow.value[targetWorktreePath],
        )
        assertEquals(
            listOf("setup detached-based worktree"),
            setupRunner.requestFor(targetWorktreePath)?.setupCommands,
        )

        setupRunner.complete(targetWorktreePath)
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromBaseRunsSetupAndRefreshesWhenExactTargetWorktreeAlreadyExists() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "789abc")
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                        Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "def456"),
                        targetWorktree,
                    ),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { targetWorktreePath.value },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup existing stacked worktree"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
        assertEquals(
            listOf("setup existing stacked worktree"),
            setupRunner.requestFor(targetWorktreePath)?.setupCommands,
        )
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            viewModel.setupStatusesStateFlow.value[targetWorktreePath],
        )

        setupRunner.complete(targetWorktreePath)
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromBaseRefreshesRepositoryWhileSetupIsRunning() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "def456")
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "789abc")
        var currentWorktrees = listOf(rootWorktree, baseWorktree)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { request ->
                    currentWorktrees = currentWorktrees + targetWorktree
                    buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup stacked worktree"),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT, baseWorktreePath)
            }
        }

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            viewModel.setupStatusesStateFlow.value[targetWorktreePath],
        )

        setupRunner.complete(targetWorktreePath)
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromBaseRefreshesRepositoryWhenSetupFinishesQuickly() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "def456")
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "789abc")
        var currentWorktrees = listOf(rootWorktree, baseWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { request ->
                    currentWorktrees = currentWorktrees + targetWorktree
                    buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = {
                WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
            },
            setupCommands = listOf("setup stacked worktree"),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT, baseWorktreePath)
            }
        }

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
        assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromBaseRefreshesRepositoryWhenNoSetupCommandsAreConfigured() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "def456")
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "789abc")
        var currentWorktrees = listOf(rootWorktree, baseWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { request ->
                    currentWorktrees = currentWorktrees + targetWorktree
                    buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = emptyList())),
            services = LocalRepositoryViewModelServices(
                worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
            ),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT, baseWorktreePath)
            }
        }

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
        assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }
}
