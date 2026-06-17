package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.GitRebaseConflictException
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
    fun rebaseLocalWorktreeOntoParentConflictPromptsAndAbortAbortsRebase() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val abortCalled = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch),
                ),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onAbortRebase = { abortCalled.complete(Unit) },
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

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, parentBranch)

        val request = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }
        assertEquals(
            RebaseConflictResolutionRequest(
                repoRootPath = DEV_LAKE_ROOT,
                worktreePath = childWorktreePath,
                parentBranch = parentBranch,
            ),
            request,
        )
        assertEquals(null, viewModel.actionErrorStateFlow.value)

        viewModel.abortRebaseAfterConflict(request!!)
        withTimeout(2_000.milliseconds) { abortCalled.await() }
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(null, viewModel.rebaseConflictResolutionRequestStateFlow.value)
    }

    @Test
    fun overlappingAbortRebaseAfterConflictRequestsForSameWorktreeAreIgnored() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val firstAbortStarted = CompletableDeferred<Unit>()
        val releaseFirstAbort = CompletableDeferred<Unit>()
        val overlappingAbortStarted = CompletableDeferred<Unit>()
        var abortAttempts = 0
        val api = conflictRebaseApi(
            childWorktreePath = childWorktreePath,
            parentBranch = parentBranch,
            callbacks = RecordingGitWorktreeApiCallbacks(
                onAbortRebase = {
                    abortAttempts += 1
                    if (abortAttempts == 1) {
                        firstAbortStarted.complete(Unit)
                        runBlocking { releaseFirstAbort.await() }
                    } else {
                        overlappingAbortStarted.complete(Unit)
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
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, parentBranch)
        val request = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.abortRebaseAfterConflict(request!!)
        withTimeout(2_000.milliseconds) { firstAbortStarted.await() }

        viewModel.abortRebaseAfterConflict(request)

        assertEquals(null, withTimeoutOrNull(100.milliseconds) { overlappingAbortStarted.await() })
        releaseFirstAbort.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(null, viewModel.rebaseConflictResolutionRequestStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun failedAbortRebaseAfterConflictKeepsPromptVisibleForRetry() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch),
                ),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
                abortRebaseFailure = RuntimeException("abort failed"),
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
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, parentBranch)
        val request = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }

        viewModel.abortRebaseAfterConflict(request!!)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("abort failed", actionError?.message)
        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(request, viewModel.rebaseConflictResolutionRequestStateFlow.value)
    }

    @Test
    fun multipleRebaseConflictsAreQueuedUntilHandled() = runBlocking {
        val firstChildWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val secondChildWorktreePath = "$DEV_LAKE_ROOT-feature-next-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = firstChildWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
            Worktree(path = secondChildWorktreePath, branch = "feature/next-pr", commitHash = "987abc"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf(
                        "feature/stacked-pr" to parentBranch,
                        "feature/next-pr" to parentBranch,
                    ),
                ),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = firstChildWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
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
                repositories.single().worktrees.size == 3
            }
        }
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, firstChildWorktreePath, parentBranch)
        val firstRequest = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, secondChildWorktreePath, parentBranch)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first {
                api.rebaseWorktreeOntoParentCalls.size == 2 && it.isEmpty()
            }
        }

        val secondRequest = RebaseConflictResolutionRequest(
            repoRootPath = DEV_LAKE_ROOT,
            worktreePath = secondChildWorktreePath,
            parentBranch = parentBranch,
        )
        assertEquals(firstRequest, viewModel.rebaseConflictResolutionRequestStateFlow.value)

        viewModel.leaveRebaseConflictAsIs(firstRequest!!)

        assertEquals(secondRequest, viewModel.rebaseConflictResolutionRequestStateFlow.value)
    }

    @Test
    fun rebaseLocalWorktreeOntoParentConflictLeaveAsIsClearsPromptWithoutAborting() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch),
                ),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
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

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, parentBranch)

        val request = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.leaveRebaseConflictAsIs(request!!)

        assertEquals(emptyList(), api.abortRebaseCalls)
        assertEquals(null, viewModel.rebaseConflictResolutionRequestStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun staleRebaseConflictAbortRequestDoesNotAbort() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch),
                ),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onAbortRebase = {
                    abortStarted.complete(Unit)
                    runBlocking { releaseAbort.await() }
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

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, parentBranch)

        val request = withTimeout(2_000.milliseconds) {
            viewModel.rebaseConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.leaveRebaseConflictAsIs(request!!)
        viewModel.abortRebaseAfterConflict(request)

        val staleAbortStarted = withTimeoutOrNull(100.milliseconds) { abortStarted.await() }
        releaseAbort.complete(Unit)

        assertEquals(null, staleAbortStarted)
        assertEquals(emptyList(), api.abortRebaseCalls)
        assertEquals(emptySet(), viewModel.rebasingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    private fun conflictRebaseApi(
        @Suppress("SameParameterValue") childWorktreePath: String,
        @Suppress("SameParameterValue") parentBranch: String,
        callbacks: RecordingGitWorktreeApiCallbacks = RecordingGitWorktreeApiCallbacks(),
    ): RecordingGitWorktreeApi {
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        return RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch)),
                rebaseWorktreeFailure = GitRebaseConflictException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    cause = RuntimeException("conflict"),
                ),
            ),
            callbacks = callbacks,
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
