package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeRebaseViewModelTest {

    @Test
    fun rebaseLocalWorktreeOntoParentRebasesAndRefreshesRepository() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "abc123")
        val childBeforeRebase = Worktree(
            path = childWorktreePath,
            branch = "feature/stacked-pr",
            commitHash = "def456",
            isDirty = true,
        )
        val childAfterRebase = childBeforeRebase.copy(isDirty = false)
        var currentWorktrees = listOf(baseWorktree, childBeforeRebase)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onRebaseWorktreeOntoParent = { call ->
                    assertEquals(RebaseWorktreeOntoParentCall(childWorktreePath, "feature/base-pr"), call)
                    currentWorktrees = listOf(baseWorktree, childAfterRebase)
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
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.any { it.isDirty }
            }
        }

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.none { it.isDirty }
            }.single()
        }

        assertEquals(
            listOf(RebaseWorktreeOntoParentCall(childWorktreePath, "feature/base-pr")),
            api.rebaseWorktreeOntoParentCalls,
        )
        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.rebasingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun overlappingRebaseLocalWorktreeOntoParentRequestsForSameWorktreeAreIgnored() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = "feature/base-pr", commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val firstRebaseStarted = CompletableDeferred<Unit>()
        val releaseFirstRebase = CompletableDeferred<Unit>()
        val overlappingRebaseStarted = CompletableDeferred<Unit>()
        var rebaseAttempts = 0
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onRebaseWorktreeOntoParent = {
                    rebaseAttempts += 1
                    if (rebaseAttempts == 1) {
                        firstRebaseStarted.complete(Unit)
                        runBlocking { releaseFirstRebase.await() }
                    } else {
                        overlappingRebaseStarted.complete(Unit)
                    }
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
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")
        withTimeout(2_000.milliseconds) {
            firstRebaseStarted.await()
        }
        assertEquals(setOf(childWorktreePath), viewModel.rebasingLocalWorktreePathsStateFlow.value)

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        assertEquals(null, withTimeoutOrNull(100.milliseconds) { overlappingRebaseStarted.await() })
        releaseFirstRebase.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(
            listOf(RebaseWorktreeOntoParentCall(childWorktreePath, "feature/base-pr")),
            api.rebaseWorktreeOntoParentCalls,
        )
    }

    @Test
    fun rebaseLocalWorktreeOntoParentFailureSetsActionErrorAndRefreshesRepositoryBestEffort() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = "feature/base-pr", commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
                rebaseWorktreeFailure = IllegalStateException("rebase failed"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("rebase failed", actionError?.message)
        assertEquals(
            listOf(RebaseWorktreeOntoParentCall(childWorktreePath, "feature/base-pr")),
            api.rebaseWorktreeOntoParentCalls,
        )
        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(
            listOf("feature/base-pr", "feature/stacked-pr"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
        assertEquals(emptySet(), viewModel.rebasingLocalWorktreePathsStateFlow.value)
    }
}
