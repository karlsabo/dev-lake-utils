package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeArchiveViewModelTest {

    @Test
    fun archivingCleanNonRootWorktreeArchivesAndRefreshesRepository() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onArchiveWorktree = { repoPath, worktreePath, _ ->
                    assertEquals(DEV_LAKE_ROOT, repoPath)
                    assertEquals(DEV_LAKE_SELECTED_WORKTREE, worktreePath)
                    currentWorktrees = listOf(rootWorktree)
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

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun dirtyArchiveFailurePromptsForForceArchiveThenForceArchivesAndRefreshesRepository() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
            isDirty = true,
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onArchiveWorktree = { _, _, force ->
                    if (!force) error("fatal: contains modified files")
                    currentWorktrees = listOf(rootWorktree)
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

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }
        assertEquals(DEV_LAKE_ROOT, forceRequest.repoRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, forceRequest.worktreePath)
        assertEquals(null, viewModel.actionErrorStateFlow.value)

        viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(
            listOf(
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
            ),
            api.archiveWorktreeCalls,
        )
        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun cleanupFailureForDirtyPathDoesNotPromptForForceArchive() = runBlocking {
        val dirtyPath = "$DEV_LAKE_ROOT-dirty-cleanup"
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = dirtyPath,
            branch = "feature/dirty-cleanup",
            commitHash = "def456",
        )
        val worktrees = listOf(rootWorktree, featureWorktree)
        val failureMessage = "Failed to delete leftover worktree directory at $dirtyPath"
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onArchiveWorktree = { _, _, _ ->
                    throw IllegalStateException(failureMessage)
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

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, dirtyPath)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(failureMessage, actionError?.message)
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(listOf(DEV_LAKE_ROOT to dirtyPath), api.archiveWorktreeCalls)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun forceArchiveConfirmationStartsWhileDirtyFailureRefreshIsStillRunning() = runBlocking {
        val fixture = ForceArchiveRaceFixture()
        val api = fixture.api
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
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }
        withTimeout(2_000.milliseconds) {
            fixture.dirtyFailureRefreshStarted.await()
        }

        try {
            viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

            withTimeout(2_000.milliseconds) {
                fixture.forceArchiveStarted.await()
            }
        } finally {
            fixture.releaseDirtyFailureRefresh.complete(Unit)
        }
        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }

        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun forceArchiveFailureSetsActionErrorAndLeavesRowsVisible() = runBlocking {
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(
                path = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/worktree-panel",
                commitHash = "def456",
                isDirty = true,
            ),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onArchiveWorktree = { _, _, force ->
                    if (force) error("force archive failed")
                    error("fatal: contains modified files")
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
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val forceRequest = withTimeout(2_000.milliseconds) {
            viewModel.forceArchiveWorktreeRequestStateFlow.first { it != null }!!
        }

        viewModel.confirmForceArchiveLocalWorktree(forceRequest.repoRootPath, forceRequest.worktreePath)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("force archive failed", actionError?.message)
        assertEquals(listOf(false, true), api.archiveWorktreeForceValues)
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
        assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun archivingWorktreeFailureSetsActionErrorWithoutRefreshingRows() = runBlocking {
        val worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(
                path = DEV_LAKE_SELECTED_WORKTREE,
                branch = "feature/worktree-panel",
                commitHash = "def456",
            ),
        )
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
                archiveWorktreeFailure = IllegalStateException("archive failed"),
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

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("archive failed", actionError?.message)
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(
            listOf("main", "feature/worktree-panel"),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.branch },
        )
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    @Test
    fun archivingPostRemoveFailureRefreshesRepositoryRows() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onArchiveWorktree = { _, _, _ ->
                    currentWorktrees = listOf(rootWorktree)
                    error("cleanup failed")
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

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val repository = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
            }.single()
        }
        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("cleanup failed", actionError?.message)
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
    }

    private class ForceArchiveRaceFixture {
        private val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        private val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
            isDirty = true,
        )
        private var currentWorktrees = listOf(rootWorktree, featureWorktree)
        private var archiveAttempts = 0
        val dirtyFailureRefreshStarted = CompletableDeferred<Unit>()
        val releaseDirtyFailureRefresh = CompletableDeferred<Unit>()
        val forceArchiveStarted = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(worktreesForRepoPath = { currentWorktrees }),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = { recordListWorktrees() },
                onArchiveWorktree = { _, _, force -> recordArchiveWorktree(force) },
            ),
        )

        private fun recordListWorktrees() {
            if (archiveAttempts == 1) {
                dirtyFailureRefreshStarted.complete(Unit)
                runBlocking { releaseDirtyFailureRefresh.await() }
            }
        }

        private fun recordArchiveWorktree(force: Boolean) {
            archiveAttempts += 1
            if (!force) error("fatal: contains modified files")
            forceArchiveStarted.complete(Unit)
            currentWorktrees = listOf(rootWorktree)
        }
    }
}
