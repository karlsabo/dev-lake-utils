package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.GitRebaseConflictException
import com.github.karlsabo.git.OriginFetchFailureException
import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        )
        val childAfterRebase = childBeforeRebase.copy(commitHash = "fed654")
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
        viewModel.localRepositoriesStateFlow.first { repositories ->
            repositories.single().worktrees.singleOrNull { it.branch == "feature/stacked-pr" }
                ?.parentBranch == "feature/base-pr" && repositories.single().operationRequest == null
        }
        assertEquals(listOf(DEV_LAKE_ROOT), api.listWorktreeEntryRepoPaths)

        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, childWorktreePath, "feature/base-pr")
        viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }

        assertEquals(listOf(DEV_LAKE_ROOT, DEV_LAKE_ROOT), api.listWorktreeEntryRepoPaths)
        assertEquals(emptyList(), api.listWorktreesWithStatusRepoPaths)

        assertEquals(
            listOf(RebaseWorktreeOntoParentCall(childWorktreePath, "feature/base-pr")),
            api.rebaseWorktreeOntoParentCalls,
        )
        assertEquals(
            listOf("feature/base-pr", "feature/stacked-pr"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }
        assertEquals(
            WorktreeConflictResolutionRequest(
                operation = WorktreeIntegrationOperation.Rebase,
                repoRootPath = DEV_LAKE_ROOT,
                worktreePath = childWorktreePath,
                parentBranch = parentBranch,
            ),
            request,
        )
        assertEquals(null, viewModel.actionErrorStateFlow.value)

        viewModel.abortWorktreeConflict(request!!)
        withTimeout(2_000.milliseconds) { abortCalled.await() }
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(null, viewModel.worktreeConflictResolutionRequestStateFlow.value)
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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.abortWorktreeConflict(request!!)
        withTimeout(2_000.milliseconds) { firstAbortStarted.await() }

        viewModel.abortWorktreeConflict(request)

        assertEquals(null, withTimeoutOrNull(100.milliseconds) { overlappingAbortStarted.await() })
        releaseFirstAbort.complete(Unit)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(null, viewModel.worktreeConflictResolutionRequestStateFlow.value)
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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }

        viewModel.abortWorktreeConflict(request!!)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("abort failed", actionError?.message)
        assertEquals(listOf(AbortRebaseCall(childWorktreePath)), api.abortRebaseCalls)
        assertEquals(request, viewModel.worktreeConflictResolutionRequestStateFlow.value)
    }

    @Test
    fun multipleRebaseConflictsAreQueuedUntilHandled() = runBlocking {
        val firstChildWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val secondChildWorktreePath = "$DEV_LAKE_ROOT-feature-next-pr"
        val refreshStarted = Channel<Unit>(Channel.UNLIMITED)
        val parentBranch = "feature/base-pr"
        val worktrees = conflictingWorktrees(parentBranch, firstChildWorktreePath, secondChildWorktreePath)
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
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktreeEntries = { refreshStarted.trySend(Unit).getOrThrow() },
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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }
        // A first-conflict refresh can start after its prompt is visible; it must not satisfy the second wait.
        withTimeout(2_000.milliseconds) {
            refreshStarted.receive()
            refreshStarted.receive()
        }
        viewModel.rebaseLocalWorktreeOntoParent(DEV_LAKE_ROOT, secondChildWorktreePath, parentBranch)
        withTimeout(2_000.milliseconds) { refreshStarted.receive() }

        val secondRequest = WorktreeConflictResolutionRequest(
            operation = WorktreeIntegrationOperation.Rebase,
            repoRootPath = DEV_LAKE_ROOT,
            worktreePath = secondChildWorktreePath,
            parentBranch = parentBranch,
        )
        assertEquals(firstRequest, viewModel.worktreeConflictResolutionRequestStateFlow.value)

        viewModel.leaveWorktreeConflictAsIs(firstRequest!!)

        assertEquals(secondRequest, viewModel.worktreeConflictResolutionRequestStateFlow.value)
    }

    private fun conflictingWorktrees(
        parentBranch: String,
        firstChildPath: String,
        secondChildPath: String,
    ) = listOf(
        Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
        Worktree(path = firstChildPath, branch = "feature/stacked-pr", commitHash = "def456"),
        Worktree(path = secondChildPath, branch = "feature/next-pr", commitHash = "987abc"),
    )

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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.leaveWorktreeConflictAsIs(request!!)

        assertEquals(emptyList(), api.abortRebaseCalls)
        assertEquals(null, viewModel.worktreeConflictResolutionRequestStateFlow.value)
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
            viewModel.worktreeConflictResolutionRequestStateFlow.first { it != null }
        }
        viewModel.leaveWorktreeConflictAsIs(request!!)
        viewModel.abortWorktreeConflict(request)

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
    fun originFetchFailureReportsUnderlyingGitOutputWithoutPromptingForConflictRecovery() = runBlocking {
        val childWorktreePath = "$DEV_LAKE_ROOT-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val worktrees = listOf(
            Worktree(path = "$DEV_LAKE_ROOT-feature-base-pr", branch = parentBranch, commitHash = "abc123"),
            Worktree(path = childWorktreePath, branch = "feature/stacked-pr", commitHash = "def456"),
        )
        val gitOutput = "fatal: could not read Username for 'https://github.com': terminal prompts disabled"
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                parentBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to mapOf("feature/stacked-pr" to parentBranch),
                ),
                rebaseWorktreeFailure = OriginFetchFailureException(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                    gitOutput = gitOutput,
                    cause = RuntimeException("fetch failed"),
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

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertTrue(actionError?.message.orEmpty().contains(gitOutput))
        assertEquals(null, viewModel.worktreeConflictResolutionRequestStateFlow.value)
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(emptySet(), viewModel.rebasingLocalWorktreePathsStateFlow.value)
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
        withTimeout(2_000.milliseconds) {
            viewModel.rebasingLocalWorktreePathsStateFlow.first { it.isEmpty() }
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
