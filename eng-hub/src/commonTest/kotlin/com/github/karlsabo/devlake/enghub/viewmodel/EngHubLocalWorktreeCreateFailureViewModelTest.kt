package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.ExistingTargetBranchAncestryException
import com.github.karlsabo.git.GitWorktreeException
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeCreateFailureViewModelTest {

    @Test
    fun createLocalWorktreeFromBaseRefreshesRepositoryWhenSetupFailsAfterCreate() = runBlocking {
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
            setupRunner = { throw IllegalStateException("setup failed after create") },
            setupCommands = listOf("fail setup"),
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

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertEquals("setup failed after create", actionError?.message)
        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
    }

    @Test
    fun createLocalWorktreeFromBaseRefreshesAgainWhenSetupFailsAfterPostCreateRefreshFailure() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "def456")
        val targetWorktree = Worktree(path = targetWorktreePath.value, branch = targetBranch, commitHash = "789abc")
        var currentWorktrees = listOf(rootWorktree, baseWorktree)
        val listCalls = MutableStateFlow(0)
        val setupRunner = FailingBlockingSetupRunner { "Setup commands failed for ${it.value} with exit code 23" }
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = {
                    val nextCallCount = listCalls.value + 1
                    listCalls.value = nextCallCount
                    if (nextCallCount == 2) error("transient worktree list failure")
                    currentWorktrees
                },
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
            setupCommands = listOf("exit 23"),
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
        withTimeout(2_000.milliseconds) { listCalls.first { it >= 2 } }

        setupRunner.fail(targetWorktreePath)
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.path == targetWorktreePath.value }
            }.single()
        }

        assertTrue(actionError?.message?.contains("exit code 23") == true)
        assertEquals(listOf("main", "feature/base-pr", targetBranch), repository.worktrees.map { it.branch })
    }

    @Test
    fun createLocalWorktreeFromBaseSetupFailureSetsActionError() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val setupRunner = FailingBlockingSetupRunner { "setup failed after create" }
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { request -> buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("fail setup"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        setupRunner.fail(targetWorktreePath)
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("setup failed after create", actionError?.message)
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
        withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { targetWorktreePath !in it }
        }
        assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
    }

    @Test
    fun createLocalWorktreeFromBaseShowsBranchCheckedOutElsewhereError() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = {
                    throw GitWorktreeException(
                        "Branch feature/stacked-pr is already checked out elsewhere at " +
                            "/repos/dev-lake-utils-feature-stacked-pr-existing. Choose a different branch name.",
                    )
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("should not run"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(
            "Branch feature/stacked-pr is already checked out elsewhere at " +
                "/repos/dev-lake-utils-feature-stacked-pr-existing. Choose a different branch name.",
            actionError?.message,
        )
        assertEquals(0, setupRunner.calls())
    }

    @Test
    fun createLocalWorktreeFromBaseAsksForConfirmationWhenExistingBranchIsUnrelated() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = createExistingBranchWorktreeOrRequestConfirmation(),
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup unrelated existing branch"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        val confirmation = withTimeout(2_000.milliseconds) {
            viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.first { it != null }
        }

        assertEquals(
            UseUnrelatedExistingBranchConfirmationRequest(
                repoRootPath = DEV_LAKE_ROOT,
                baseWorktreePath = baseWorktreePath,
                baseBranch = "feature/base-pr",
                targetBranch = targetBranch,
            ),
            confirmation,
        )
        assertEquals(null, viewModel.actionErrorStateFlow.value)
        assertEquals(0, setupRunner.calls())
    }

    @Test
    fun confirmUseUnrelatedExistingBranchCreatesExistingBranchWorktreeAndRunsSetup() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = createExistingBranchWorktreeOrRequestConfirmation(targetWorktreePath.value),
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("setup unrelated existing branch"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        val confirmation = withTimeout(2_000.milliseconds) {
            viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.first { it != null }
        }

        viewModel.confirmUseUnrelatedExistingBranch(requireNotNull(confirmation))
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(targetWorktreePath) }

        assertEquals(null, viewModel.useUnrelatedExistingBranchConfirmationRequestStateFlow.value)
        assertEquals(
            listOf(
                CreateBranchWorktreeCall(
                    repoPath = DEV_LAKE_ROOT,
                    baseWorktreePath = baseWorktreePath,
                    baseBranch = "feature/base-pr",
                    targetBranch = targetBranch,
                ),
                CreateBranchWorktreeCall(
                    repoPath = DEV_LAKE_ROOT,
                    baseWorktreePath = baseWorktreePath,
                    baseBranch = "feature/base-pr",
                    targetBranch = targetBranch,
                    allowUnrelatedExistingBranch = true,
                ),
            ),
            api.createBranchWorktreeCalls,
        )
        assertEquals(
            listOf("setup unrelated existing branch"),
            setupRunner.requestFor(targetWorktreePath)?.setupCommands,
        )

        setupRunner.complete(targetWorktreePath)
    }

    @Test
    fun createLocalWorktreeFromBaseShowsRemoteBranchConflictError() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = {
                    throw GitWorktreeException(
                        "Remote branch origin/feature/stacked-pr already exists. Choose a different branch name.",
                    )
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("should not run"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(
            "Remote branch origin/feature/stacked-pr already exists. Choose a different branch name.",
            actionError?.message,
        )
        assertEquals(0, setupRunner.calls())
    }

    @Test
    fun createLocalWorktreeFromBaseCreationFailureSetsActionErrorWithoutRunningSetup() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(DEV_LAKE_ROOT, targetBranch)
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onCreateBranchWorktree = { throw IllegalStateException("git worktree add failed") },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("should not run"),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = baseWorktreePath,
            baseBranch = "feature/base-pr",
            targetBranch = targetBranch,
        )
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("git worktree add failed", actionError?.message)
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
        assertEquals(0, setupRunner.calls())
        assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
        assertEquals(null, setupRunner.requestFor(targetWorktreePath))
    }
}

private fun createExistingBranchWorktreeOrRequestConfirmation(
    targetWorktreePath: String? = null,
): (CreateBranchWorktreeCall) -> String = { request ->
    if (!request.allowUnrelatedExistingBranch) {
        throw ExistingTargetBranchAncestryException(
            baseBranch = request.baseBranch,
            targetBranch = request.targetBranch,
        )
    }
    targetWorktreePath ?: buildWorktreePath(DEV_LAKE_ROOT, request.targetBranch).value
}
