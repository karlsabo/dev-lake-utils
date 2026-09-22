package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.karlsabo.git.BaseBranchOriginFetchFailureException
import com.github.karlsabo.git.GitWorktreeException
import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeUpdateViewModelTest {
    @Test
    fun updateFromOriginUpdatesDefaultBranchAndRefreshesRepository() = runBlocking {
        val before = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123", isDirty = true)
        val after = before.copy(commitHash = "def456", isDirty = false)
        var currentWorktrees = listOf(before)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onUpdateWorktreeFromOrigin = { call ->
                    assertEquals(UpdateWorktreeFromOriginCall(DEV_LAKE_ROOT, "main"), call)
                    currentWorktrees = listOf(after)
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.singleOrNull()?.isDirty == true }
        }

        val refreshCountBeforeUpdate = api.listWorktreeRepoPaths.size
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_ROOT, "main")

        val updated = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first {
                it.single().worktrees.singleOrNull()?.isDirty == false
            }
        }
        assertEquals(listOf(UpdateWorktreeFromOriginCall(DEV_LAKE_ROOT, "main")), api.updateWorktreeFromOriginCalls)
        assertEquals(refreshCountBeforeUpdate + 1, api.listWorktreeRepoPaths.size)
        assertEquals(true, updated.single().worktrees.single().canUpdateFromOrigin)
        withTimeout(2_000.milliseconds) {
            viewModel.updatingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun unpublishedHistoryFailureLeavesWorktreeUnchangedAndReportsRefs() = runBlocking {
        val worktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "local-commit")
        val failureMessage = "Cannot update local branch main from origin/main because main contains commits absent " +
            "from origin/main. Reconcile main with origin/main manually before updating."
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf(worktree)),
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
                updateWorktreeFailure = IllegalStateException(failureMessage),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        val displayedWorktree = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.singleOrNull() != null }
                .single().worktrees.single()
        }

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_ROOT, "main")

        val error = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals(failureMessage, error?.message)
        assertEquals(displayedWorktree, viewModel.localRepositoriesStateFlow.value.single().worktrees.single())
        assertEquals(listOf(UpdateWorktreeFromOriginCall(DEV_LAKE_ROOT, "main")), api.updateWorktreeFromOriginCalls)
    }

    @Test
    fun cachedEligibilityKeepsUpdateInvokableAndFetchFailureReportsContextAndGitOutput() = runBlocking {
        val worktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "local-commit")
        val gitOutput = "fatal: Authentication failed"
        val failure = BaseBranchOriginFetchFailureException(
            worktreePath = DEV_LAKE_ROOT,
            branch = "main",
            gitOutput = gitOutput,
            cause = IllegalStateException(gitOutput),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to listOf(worktree)),
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
                updateWorktreeFailure = failure,
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        val displayedWorktree = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first {
                it.single().worktrees.singleOrNull()?.canUpdateFromOrigin == true
            }.single().worktrees.single()
        }
        val refreshCountBeforeUpdate = api.listWorktreeRepoPaths.size

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_ROOT, "main")

        val error = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals(failure.message, error?.message)
        assertEquals(refreshCountBeforeUpdate, api.listWorktreeRepoPaths.size)
        assertEquals(displayedWorktree, viewModel.localRepositoriesStateFlow.value.single().worktrees.single())
        assertEquals(listOf(UpdateWorktreeFromOriginCall(DEV_LAKE_ROOT, "main")), api.updateWorktreeFromOriginCalls)
    }

    @Test
    fun autostashRestorationFailureRefreshesRepositoryAndUsesActionErrorMechanism() = runBlocking {
        val before = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123", isDirty = true)
        val after = before.copy(commitHash = "def456", isDirty = false)
        var currentWorktrees = listOf(before)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
                originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onUpdateWorktreeFromOrigin = {
                    currentWorktrees = listOf(after)
                    throw GitWorktreeException("autostash restoration failed")
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.singleOrNull()?.isDirty == true }
        }

        val refreshCountBeforeUpdate = api.listWorktreeRepoPaths.size
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_ROOT, "main")

        val updated = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first {
                it.single().worktrees.singleOrNull()?.isDirty == false
            }
        }
        val error = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals(listOf(UpdateWorktreeFromOriginCall(DEV_LAKE_ROOT, "main")), api.updateWorktreeFromOriginCalls)
        assertEquals(refreshCountBeforeUpdate + 1, api.listWorktreeRepoPaths.size)
        assertEquals(false, updated.single().worktrees.single().isDirty)
        withTimeout(2_000.milliseconds) {
            viewModel.updatingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals("autostash restoration failed", error?.message)
    }

    @Test
    fun updateTracksNormalizedPathAndExcludesConcurrentIntegrations() = runBlocking {
        val updateStarted = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        val normalizedPath = "$DEV_LAKE_ROOT-feature-base"
        val unnormalizedPath = "$DEV_LAKE_ROOT-feature/../dev-lake-utils-feature-base/"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = normalizedPath, branch = "main", commitHash = "abc123")),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onUpdateWorktreeFromOrigin = {
                    updateStarted.complete(Unit)
                    runBlocking { releaseUpdate.await() }
                },
            ),
        )
        val viewModel = updateViewModel(api)

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, unnormalizedPath, "main")
        withTimeout(2_000.milliseconds) { updateStarted.await() }

        assertEquals(setOf(normalizedPath), viewModel.updatingLocalWorktreePathsStateFlow.value)
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, normalizedPath, "main")
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, normalizedPath, "develop")
        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, normalizedPath, "develop")
        assertEquals(listOf(UpdateWorktreeFromOriginCall(unnormalizedPath, "main")), api.updateWorktreeFromOriginCalls)
        assertEquals(emptyList(), api.rebaseWorktreeOntoParentCalls)
        assertEquals(emptyList(), api.mergeWorktreeWithParentCalls)

        releaseUpdate.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.updatingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(listOf(UpdateWorktreeFromOriginCall(unnormalizedPath, "main")), api.updateWorktreeFromOriginCalls)
    }

    @Test
    fun staleWorktreeCallbacksCannotMutatePathWhileUpdateRuns() = runBlocking {
        val updateStarted = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        val worktreePath = DEV_LAKE_SELECTED_WORKTREE
        val aliasedWorktreePath = "/repos/other/../dev-lake-utils-feature-worktree-panel/"
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            callbacks = RecordingGitWorktreeApiCallbacks(
                onUpdateWorktreeFromOrigin = {
                    updateStarted.complete(Unit)
                    runBlocking { releaseUpdate.await() }
                },
            ),
        )
        val viewModel = createWorktreeSetupViewModel(
            gitWorktreeApi = api,
            setupRunner = setupRunner,
            setupCommands = listOf("./setup.sh"),
        )

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, aliasedWorktreePath, "main")
        withTimeout(2_000.milliseconds) { updateStarted.await() }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, worktreePath)
        viewModel.openLocalWorktree(DEV_LAKE_ROOT, worktreePath)
        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = worktreePath,
            baseBranch = "feature/worktree-panel",
            targetBranch = "feature/child",
        )

        assertEquals(emptyList(), api.archiveWorktreeCalls)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
        assertEquals(0, setupRunner.calls())
        assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)

        releaseUpdate.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.updatingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        Unit
    }

    @Test
    fun updateIsExcludedWhileAnotherIntegrationRuns() = runBlocking {
        val rebaseStarted = CompletableDeferred<Unit>()
        val releaseRebase = CompletableDeferred<Unit>()
        val worktreePath = "$DEV_LAKE_ROOT-feature-base"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = worktreePath, branch = "main", commitHash = "abc123")),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onRebaseWorktreeOntoParent = {
                    rebaseStarted.complete(Unit)
                    runBlocking { releaseRebase.await() }
                },
            ),
        )
        val viewModel = updateViewModel(api)

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, worktreePath, "develop")
        withTimeout(2_000.milliseconds) { rebaseStarted.await() }
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, worktreePath, "main")

        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
        assertEquals(emptySet(), viewModel.updatingLocalWorktreePathsStateFlow.value)
        releaseRebase.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        Unit
    }

    @Test
    fun updateFailureReleasesProgressAndExclusion() = runBlocking {
        val worktreePath = "$DEV_LAKE_ROOT-feature-base"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = worktreePath, branch = "main", commitHash = "abc123")),
                ),
                updateWorktreeFailure = IllegalStateException("update failed"),
            ),
        )
        val viewModel = updateViewModel(api)

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, worktreePath, "main")
        withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
            viewModel.updatingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, worktreePath, "develop")
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals(listOf(RebaseWorktreeOntoParentCall(worktreePath, "develop")), api.rebaseWorktreeOntoParentCalls)
    }

    @Test
    fun cancellingUpdateReleasesProgressAndExclusion() = runBlocking {
        val updateStarted = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        val worktreePath = "$DEV_LAKE_ROOT-feature-base"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(Worktree(path = worktreePath, branch = "main", commitHash = "abc123")),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onUpdateWorktreeFromOrigin = {
                    updateStarted.complete(Unit)
                    runBlocking { releaseUpdate.await() }
                },
            ),
        )
        val viewModel = updateViewModel(api)
        val existingJobs = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, worktreePath, "main")
        withTimeout(2_000.milliseconds) { updateStarted.await() }
        val updateJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }
        updateJob.cancel()
        releaseUpdate.complete(Unit)
        updateJob.cancelAndJoin()

        assertEquals(emptySet(), viewModel.updatingLocalWorktreePathsStateFlow.value)
        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, worktreePath, "develop")
        withTimeout(2_000.milliseconds) {
            viewModel.mergingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(listOf(MergeWorktreeWithParentCall(worktreePath, "develop")), api.mergeWorktreeWithParentCalls)
    }

    private fun updateViewModel(api: RecordingGitWorktreeApi): EngHubViewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
    )
}
