package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreeSetupCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

private data class ArchiveControllerFixture(
    val state: EngHubViewModelState,
    val controller: LocalWorktreeArchiveController,
)

private fun createArchiveControllerFixture(api: RecordingGitWorktreeApi): ArchiveControllerFixture {
    val configWriter = RecordingEngHubConfigWriter()
    val services = EngHubWorktreeServices(
        gitWorktreeApi = api,
        worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = api),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = configWriter,
    )
    val state = EngHubViewModelState(
        config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
        configWriter = configWriter,
        worktreeSetupCoordinator = services.worktreeSetupCoordinator,
        notificationIgnoreStore = NoOpNotificationIgnoreStore(),
    )
    val viewModel = object : ViewModel() {}
    val errorReporter = ActionErrorReporter(state)
    val repositories = LocalRepositoryController(viewModel, state, services, errorReporter)
    val controller = LocalWorktreeArchiveController(
        viewModel,
        state,
        services,
        repositories,
        errorReporter,
        worktreeRemovalWaitTimeout = 25.milliseconds,
    )
    repositories.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
    return ArchiveControllerFixture(state, controller)
}

private suspend fun awaitArchiveCompletion(viewModel: EngHubViewModel) {
    withTimeout(2_000.milliseconds) {
        viewModel.archivingLocalWorktreePathsStateFlow.first { it.isEmpty() }
    }
}

private suspend fun retryArchiveWhileBlocked(viewModel: EngHubViewModel) {
    repeat(50) {
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        delay(10.milliseconds)
    }
}

private suspend fun expandRepository(viewModel: EngHubViewModel) {
    viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
    withTimeout(2_000.milliseconds) {
        viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == 2 }
    }
}

private fun assertArchiveIsGuarded(viewModel: EngHubViewModel, api: RecordingGitWorktreeApi) {
    assertEquals(setOf(DEV_LAKE_SELECTED_WORKTREE), viewModel.archivingLocalWorktreePathsStateFlow.value)
    assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
    assertEquals(
        listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
        viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
    )
}

private fun blockedRefreshCalls(
    firstRefreshStarted: CompletableDeferred<Unit>,
    releaseFirstRefresh: CompletableDeferred<Unit>,
    secondRefreshStarted: CompletableDeferred<Unit>,
    releaseSecondRefresh: CompletableDeferred<Unit>,
) = Channel<() -> Unit>(capacity = 3).apply {
    trySend {}
    trySend {
        firstRefreshStarted.complete(Unit)
        runBlocking { releaseFirstRefresh.await() }
    }
    trySend {
        secondRefreshStarted.complete(Unit)
        runBlocking { releaseSecondRefresh.await() }
    }
}

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

        withTimeout(2_000.milliseconds) {
            viewModel.archivingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(listOf("main"), repository.worktrees.map { it.branch })
        assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun successfulArchiveRemainsGuardedUntilRefreshCompletes() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        var archiveCompleted = false
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = {
                    if (archiveCompleted) {
                        refreshStarted.complete(Unit)
                        runBlocking { releaseRefresh.await() }
                    }
                },
                onArchiveWorktree = { _, _, _ ->
                    currentWorktrees = listOf(rootWorktree)
                    archiveCompleted = true
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
        withTimeout(2_000.milliseconds) { refreshStarted.await() }

        try {
            assertEquals(
                setOf(DEV_LAKE_SELECTED_WORKTREE),
                viewModel.archivingLocalWorktreePathsStateFlow.value,
            )
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        } finally {
            releaseRefresh.complete(Unit)
        }
        withTimeout(2_000.milliseconds) {
            viewModel.archivingLocalWorktreePathsStateFlow.first { it.isEmpty() }
        }

        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        assertEquals(
            listOf(DEV_LAKE_ROOT),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
    }

    @Test
    fun failedArchiveRefreshEventuallyAllowsRetryWhileRowsRemainStale() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var archiveCompleted = false
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { listOf(rootWorktree, featureWorktree) },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = {
                    if (archiveCompleted) error("refresh failed")
                },
                onArchiveWorktree = { _, _, _ -> archiveCompleted = true },
            ),
        )
        val (state, archiveController) = createArchiveControllerFixture(api)
        withTimeout(2_000.milliseconds) {
            state.localRepositories.first { repositories ->
                repositories.single().worktrees.size == 2
            }
        }
        archiveController.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            state.archivingLocalWorktreePaths.first { it.isEmpty() }
        }

        archiveController.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        withTimeout(2_000.milliseconds) {
            state.archivingLocalWorktreePaths.first {
                api.archiveWorktreeCalls.size == 2 && it.isEmpty()
            }
        }
        assertEquals(
            listOf(
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
                DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE,
            ),
            api.archiveWorktreeCalls,
        )
        assertEquals(
            listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
            state.localRepositories.value.single().worktrees.map { it.path },
        )
    }

    @Test
    fun successfulArchiveRemainsGuardedAfterCollapseUntilReExpansionDiscoversRemoval() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val archiveRefreshStarted = CompletableDeferred<Unit>()
        val releaseArchiveRefresh = CompletableDeferred<Unit>()
        val reExpansionStarted = CompletableDeferred<Unit>()
        val releaseReExpansion = CompletableDeferred<Unit>()
        val listCalls = blockedRefreshCalls(
            archiveRefreshStarted,
            releaseArchiveRefresh,
            reExpansionStarted,
            releaseReExpansion,
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = { listCalls.tryReceive().getOrNull()?.invoke() },
                onArchiveWorktree = { _, _, _ ->
                    currentWorktrees = listOf(rootWorktree)
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        try {
            expandRepository(viewModel)
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { archiveRefreshStarted.await() }

            viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
            viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
            withTimeout(2_000.milliseconds) { reExpansionStarted.await() }
            releaseArchiveRefresh.complete(Unit)
            retryArchiveWhileBlocked(viewModel)

            assertArchiveIsGuarded(viewModel, api)

            releaseReExpansion.complete(Unit)
            withTimeout(2_000.milliseconds) {
                viewModel.archivingLocalWorktreePathsStateFlow.first { it.isEmpty() }
            }
        } finally {
            releaseArchiveRefresh.complete(Unit)
            releaseReExpansion.complete(Unit)
        }

        assertEquals(
            listOf(DEV_LAKE_ROOT),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
    }

    @Test
    fun successfulArchiveRemainsGuardedWhileSupersedingRefreshRuns() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val archiveRefreshStarted = CompletableDeferred<Unit>()
        val releaseArchiveRefresh = CompletableDeferred<Unit>()
        val supersedingRefreshStarted = CompletableDeferred<Unit>()
        val releaseSupersedingRefresh = CompletableDeferred<Unit>()
        val listCalls = blockedRefreshCalls(
            archiveRefreshStarted,
            releaseArchiveRefresh,
            supersedingRefreshStarted,
            releaseSupersedingRefresh,
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = { listCalls.tryReceive().getOrNull()?.invoke() },
                onArchiveWorktree = { _, _, _ ->
                    currentWorktrees = listOf(rootWorktree)
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        try {
            expandRepository(viewModel)
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { archiveRefreshStarted.await() }
            viewModel.rebaseLocalWorktreeOntoParent(
                DEV_LAKE_ROOT,
                DEV_LAKE_SELECTED_WORKTREE,
                "main",
            )
            withTimeout(2_000.milliseconds) { supersedingRefreshStarted.await() }

            releaseArchiveRefresh.complete(Unit)
            retryArchiveWhileBlocked(viewModel)

            assertArchiveIsGuarded(viewModel, api)

            releaseSupersedingRefresh.complete(Unit)
            withTimeout(2_000.milliseconds) {
                viewModel.archivingLocalWorktreePathsStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptySet(), viewModel.archivingLocalWorktreePathsStateFlow.value)
        } finally {
            releaseArchiveRefresh.complete(Unit)
            releaseSupersedingRefresh.complete(Unit)
        }
    }
}

class EngHubLocalWorktreeArchiveFailureViewModelTest {

    @Test
    fun archiveRefreshPreventsEarlierExpansionFromRestoringArchivedWorktree() = runBlocking {
        val rootWorktree = Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123")
        val featureWorktree = Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
        )
        var currentWorktrees = listOf(rootWorktree, featureWorktree)
        val expansionEnrichmentStarted = CompletableDeferred<Unit>()
        val releaseExpansionEnrichment = CompletableDeferred<Unit>()
        val enrichmentCalls = Channel<() -> Unit>(capacity = 2).apply {
            trySend {
                expansionEnrichmentStarted.complete(Unit)
                runBlocking { releaseExpansionEnrichment.await() }
            }
            trySend {}
        }
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesForRepoPath = { currentWorktrees },
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferWorktreeParentBranches = {
                    enrichmentCalls.tryReceive().getOrNull()?.invoke()
                },
                onArchiveWorktree = { _, _, _ ->
                    currentWorktrees = listOf(rootWorktree)
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )
        val jobsBeforeExpansion = viewModel.viewModelScope.coroutineContext[Job]!!.children.toSet()

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) { expansionEnrichmentStarted.await() }
        val expansionJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single {
            it !in jobsBeforeExpansion
        }

        try {
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) {
                viewModel.localRepositoriesStateFlow.first { repositories ->
                    repositories.single().worktrees.map { it.path } == listOf(DEV_LAKE_ROOT)
                }
            }
        } finally {
            releaseExpansionEnrichment.complete(Unit)
        }
        withTimeout(2_000.milliseconds) { expansionJob.join() }

        assertEquals(
            listOf(DEV_LAKE_ROOT),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
        assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
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
        awaitArchiveCompletion(viewModel)

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
