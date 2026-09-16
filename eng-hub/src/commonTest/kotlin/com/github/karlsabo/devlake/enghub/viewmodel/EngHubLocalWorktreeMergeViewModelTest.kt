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

class EngHubLocalWorktreeMergeViewModelTest {

    @Test
    fun mergeLocalWorktreeWithParentMergesAndRefreshesRepository() = runBlocking {
        val baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr"
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val baseWorktree = Worktree(path = baseWorktreePath, branch = "feature/base-pr", commitHash = "abc123")
        val childBeforeMerge = Worktree(
            path = childWorktreePath,
            branch = "feature/stacked-pr",
            commitHash = "def456",
            isDirty = true,
        )
        val childAfterMerge = childBeforeMerge.copy(isDirty = false)
        var currentWorktrees = listOf(baseWorktree, childBeforeMerge)
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onMergeWorktreeWithParent = { call ->
                    assertEquals(MergeWorktreeWithParentCall(childWorktreePath, "feature/base-pr"), call)
                    currentWorktrees = listOf(baseWorktree, childAfterMerge)
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

        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.none { it.isDirty }
            }.single()
        }

        assertEquals(
            listOf(MergeWorktreeWithParentCall(childWorktreePath, "feature/base-pr")),
            api.mergeWorktreeWithParentCalls,
        )
        assertEquals(listOf("feature/base-pr", "feature/stacked-pr"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.mergingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun mergeLocalWorktreeWithParentMergesLocalOnlyParentAndRefreshesRepository() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val localParentBranch = "feature/local-only-base"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-$localParentBranch", branch = localParentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to localParentBranch),
                ),
                originBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to emptyList()),
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

        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, childWorktreePath, localParentBranch)

        withTimeout(2_000.milliseconds) {
            viewModel.mergingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals(
            listOf(MergeWorktreeWithParentCall(childWorktreePath, localParentBranch)),
            api.mergeWorktreeWithParentCalls,
        )
        assertEquals(null, viewModel.actionErrorStateFlow.value)
        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
    }

    @Test
    fun overlappingMergeLocalWorktreeWithParentRequestsForSameWorktreeAreIgnored() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = "feature/base-pr", commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val firstMergeStarted = CompletableDeferred<Unit>()
        val releaseFirstMerge = CompletableDeferred<Unit>()
        val overlappingMergeStarted = CompletableDeferred<Unit>()
        var mergeAttempts = 0
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to "feature/base-pr"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onMergeWorktreeWithParent = {
                    mergeAttempts += 1
                    if (mergeAttempts == 1) {
                        firstMergeStarted.complete(Unit)
                        runBlocking { releaseFirstMerge.await() }
                    } else {
                        overlappingMergeStarted.complete(Unit)
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
        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")
        withTimeout(2_000.milliseconds) {
            firstMergeStarted.await()
        }
        assertEquals(setOf(childWorktreePath), viewModel.mergingLocalWorktreePathsStateFlow.value)

        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        assertEquals(null, withTimeoutOrNull(100.milliseconds) { overlappingMergeStarted.await() })
        releaseFirstMerge.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.mergingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(
            listOf(MergeWorktreeWithParentCall(childWorktreePath, "feature/base-pr")),
            api.mergeWorktreeWithParentCalls,
        )
    }

    @Test
    fun mergeLocalWorktreeWithParentFailureSetsActionErrorAndRefreshesRepositoryBestEffort() = runBlocking {
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
                mergeWorktreeFailure = IllegalStateException("merge failed"),
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

        viewModel.mergeLocalWorktreeWithParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        withTimeout(2_000.milliseconds) {
            viewModel.mergingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals("merge failed", actionError?.message)
        assertEquals(
            listOf(MergeWorktreeWithParentCall(childWorktreePath, "feature/base-pr")),
            api.mergeWorktreeWithParentCalls,
        )
        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeRepoPaths)
        assertEquals(emptySet(), viewModel.mergingLocalWorktreePathsStateFlow.value)
    }
}
