package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.component.visibleWorktreeRows
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EngHubLocalWorktreeArchiveViewModelTest {
    @Test
    fun failedStartupDiscoveryCannotReplacePersistedQueuedArchive() = runBlocking {
        val original = WorktreeArchiveJob(
            DEV_LAKE_ROOT,
            DEV_LAKE_SELECTED_WORKTREE,
            "feature/login",
            "original-queue",
            WorktreeArchiveLifecycleState.QUEUED,
            1_000,
            1_000,
            61_000,
        )
        val store = RecordingWorktreeArchiveStore().apply { jobs.value = listOf(original) }
        var discoveryCalls = 0
        val entries = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/login", commitHash = "def456"),
        )
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to entries)),
            callbacks = RecordingGitWorktreeApiCallbacks(onListWorktreeEntries = {
                discoveryCalls++
                check(discoveryCalls != 1) { "startup discovery failed" }
            }),
        )
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(10_000) }
        withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it?.message?.contains("startup discovery failed") == true }
        }
        expandRepository(viewModel)
        viewModel.clearActionError()

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it?.message?.contains("already queued") == true }
        }
        assertEquals(listOf(original), store.listJobs())
        assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun archiveQueuesPersistedWorktreeWithoutRunningGitRemoval() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi()
        val queuedAt = Instant.fromEpochMilliseconds(10_000)
        val viewModel = archiveViewModel(api, store) { queuedAt }
        expandRepository(viewModel)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val job = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        assertEquals(DEV_LAKE_ROOT, job.repositoryRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, job.worktreePath)
        assertEquals("feature/login", job.branch)
        assertEquals(WorktreeArchiveLifecycleState.QUEUED, job.state)
        assertEquals(10_000, job.queuedAtEpochMs)
        assertEquals(70_000, job.deadlineAtEpochMs)
        assertEquals(listOf(job), store.listJobs())
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun archivePersistsActualRepositoryRootInsteadOfNormalizedIdentity() = runBlocking {
        val actualRepositoryRoot = "$DEV_LAKE_ROOT/"
        val api = RecordingGitWorktreeApi(
            worktreesByRepoPath = mapOf(
                actualRepositoryRoot to listOf(
                    Worktree(path = actualRepositoryRoot, branch = "main", commitHash = "abc123"),
                    Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/login", commitHash = "def456"),
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(actualRepositoryRoot),
            services = LocalRepositoryViewModelServices(
                archiveNow = { Instant.fromEpochMilliseconds(15_000) },
            ),
        )
        viewModel.toggleLocalRepositoryExpansion(actualRepositoryRoot)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == 2 }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val job = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        assertEquals(actualRepositoryRoot, job.repositoryRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, job.worktreePath)
    }

    @Test
    fun multipleWorktreesCanBeQueuedIndependently() = runBlocking {
        val secondPath = "/repos/dev-lake-utils-feature-search"
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi(secondPath)
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(20_000) }
        expandRepository(viewModel, expectedWorktreeCount = 3)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, secondPath)

        val jobs = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.size == 2 }
        }
        assertEquals(setOf("feature/login", "feature/search"), jobs.mapTo(mutableSetOf()) { it.branch })
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun persistenceFailureReportsErrorAndDoesNotHideWorktree() = runBlocking {
        val store = RecordingWorktreeArchiveStore(IllegalStateException("disk full"))
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(30_000) }
        expandRepository(viewModel)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val error = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("Failed to queue worktree archive: disk full", error?.message)
        assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(
            listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun queuedWorktreeKeepsMutationGuard() = runBlocking {
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, RecordingWorktreeArchiveStore()) {
            Instant.fromEpochMilliseconds(40_000)
        }
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }
        }

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")

        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun undoQueuedArchiveRestoresOnlySelectedWorktreeWithoutRunningGitRemoval() = runBlocking {
        val secondPath = "/repos/dev-lake-utils-feature-search"
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi(secondPath)
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(50_000) }
        expandRepository(viewModel, expectedWorktreeCount = 3)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, secondPath)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.size == 2 }
        }

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)

        val remainingJobs = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { jobs ->
                jobs.size == 1 && jobs.single().worktreePath == secondPath
            }
        }
        assertEquals(listOf(secondPath), remainingJobs.map { it.worktreePath })
        assertEquals(listOf(secondPath), store.listJobs().map { it.worktreePath })
        assertEquals(
            listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
            visibleWorktreeRows(
                worktrees = viewModel.localRepositoriesStateFlow.value.single().worktrees,
                hiddenPaths = remainingJobs.mapTo(mutableSetOf()) { it.worktreePath },
            ).map { it.worktree.path },
        )
        assertEquals(emptyList(), api.archiveWorktreeCalls)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { jobs ->
                jobs.size == 2 && jobs.any { it.worktreePath == DEV_LAKE_SELECTED_WORKTREE }
            }
        }
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun lateUndoDoesNotDeleteRequeuedWorktree() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val deleteCalls = MutableStateFlow(0)
        val releaseFirstUndo = CompletableDeferred<Unit>()
        val releaseLateUndo = CompletableDeferred<Unit>()
        store.beforeDeleteQueuedJob = { _, _ ->
            val call = deleteCalls.value + 1
            deleteCalls.value = call
            runBlocking {
                if (call == 1) releaseFirstUndo.await() else releaseLateUndo.await()
            }
        }
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(10_000) }
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val original = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { deleteCalls.first { it == 1 } }
        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { deleteCalls.first { it == 2 } }
        releaseFirstUndo.complete(Unit)
        withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val replacement = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        kotlin.test.assertNotEquals(original.queueId, replacement.queueId)

        releaseLateUndo.complete(Unit)
        assertEquals(
            listOf(true, false),
            withTimeout(2_000.milliseconds) {
                store.deleteQueuedJobResults.first { it.size == 2 }
            },
        )
        assertEquals(listOf(replacement), store.listJobs())
        assertEquals(listOf(replacement), viewModel.queuedWorktreeArchivesStateFlow.value)
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
    }

    @Test
    fun staleDeadlineDoesNotStartRemovalForRequeuedWorktree() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi()
        val deadlineWaits = Channel<CompletableDeferred<Unit>>(capacity = Channel.UNLIMITED)
        var currentTime = Instant.fromEpochMilliseconds(10_000)
        val viewModel = archiveViewModel(
            api = api,
            store = store,
            waitForArchiveDeadline = {
                val deadlineReached = CompletableDeferred<Unit>()
                deadlineWaits.send(deadlineReached)
                deadlineReached.await()
            },
            now = { currentTime },
        )
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val original = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.singleOrNull()?.queuedAtEpochMs == 10_000L }.single()
        }
        val staleDeadline = withTimeout(2_000.milliseconds) { deadlineWaits.receive() }

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() }
        }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val replacement = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.singleOrNull()?.queueId != null }.single()
        }
        assertEquals(10_000L, replacement.queuedAtEpochMs)
        kotlin.test.assertNotEquals(original.queueId, replacement.queueId)
        withTimeout(2_000.milliseconds) { deadlineWaits.receive() }

        staleDeadline.complete(Unit)
        withTimeout(2_000.milliseconds) {
            store.transitionToRemovingCalls.first { it.size == 1 }
        }

        assertEquals(listOf(replacement), store.listJobs())
        assertEquals(listOf(replacement), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun transientClaimFailureRetriesWithoutStartingRemovalBeforeClaim() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        store.transitionFailure = IllegalStateException("store unavailable")
        val waits = Channel<Pair<Duration, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val archiveStarted = CompletableDeferred<Unit>()
        val allowArchive = CompletableDeferred<Unit>()
        val api = archiveTestApi(onArchiveWorktree = { _, _, _ ->
            archiveStarted.complete(Unit)
            runBlocking { allowArchive.await() }
        })
        val viewModel = archiveViewModel(
            api = api,
            store = store,
            waitForArchiveDeadline = { duration ->
                val release = CompletableDeferred<Unit>()
                waits.send(duration to release)
                release.await()
            },
            now = { Instant.fromEpochMilliseconds(70_000) },
        )
        try {
            expandRepository(viewModel)
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first {
                    it.singleOrNull()?.state == WorktreeArchiveLifecycleState.QUEUED
                }
            }
            val deadline = withTimeout(2_000.milliseconds) { waits.receive() }
            deadline.second.complete(Unit)
            val retry = withTimeout(2_000.milliseconds) { waits.receive() }

            assertEquals(5.seconds, retry.first)
            assertEquals(
                "Failed to start worktree archive: store unavailable",
                viewModel.actionErrorStateFlow.value?.message,
            )
            assertEquals(WorktreeArchiveLifecycleState.QUEUED, store.listJobs().single().state)
            assertEquals(emptyList(), api.archiveWorktreeCalls)

            retry.second.complete(Unit)
            withTimeout(2_000.milliseconds) { archiveStarted.await() }
            assertEquals(
                listOf(DEV_LAKE_SELECTED_WORKTREE, DEV_LAKE_SELECTED_WORKTREE),
                store.transitionToRemovingCalls.value,
            )
            assertEquals(
                WorktreeArchiveLifecycleState.REMOVING,
                viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
            )
            assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        } finally {
            allowArchive.complete(Unit)
        }
    }

    @Test
    fun deadlineAtomicallyStartsRemovalExactlyOnce() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val deadlineReached = CompletableDeferred<Unit>()
        val archiveStarted = CompletableDeferred<Unit>()
        var currentTime = Instant.fromEpochMilliseconds(10_000)
        var persistedStateAtArchiveCall: WorktreeArchiveLifecycleState? = null
        val allowArchive = CompletableDeferred<Unit>()
        val api = archiveTestApi(
            onArchiveWorktree = { _, _, _ ->
                persistedStateAtArchiveCall = store.listJobs().single().state
                archiveStarted.complete(Unit)
                runBlocking { allowArchive.await() }
            },
        )
        val viewModel = archiveViewModel(
            api = api,
            store = store,
            waitForArchiveDeadline = { deadlineReached.await() },
            now = { currentTime },
        )
        try {
            expandRepository(viewModel)
            viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first {
                    it.singleOrNull()?.state == WorktreeArchiveLifecycleState.QUEUED
                }
            }

            currentTime = Instant.fromEpochMilliseconds(70_000)
            deadlineReached.complete(Unit)
            withTimeout(2_000.milliseconds) { archiveStarted.await() }

            val removingJob = viewModel.queuedWorktreeArchivesStateFlow.value.single()
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, removingJob.state)
            assertEquals(70_000, removingJob.stateUpdatedAtEpochMs)
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, persistedStateAtArchiveCall)
            assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), store.transitionToRemovingCalls.value)
            assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
            assertEquals(listOf(false), api.archiveWorktreeForceValues)

            viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) {
                store.deleteQueuedJobCalls.first { it == listOf(DEV_LAKE_SELECTED_WORKTREE) }
            }
            assertEquals(listOf(removingJob), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(DEV_LAKE_ROOT to DEV_LAKE_SELECTED_WORKTREE), api.archiveWorktreeCalls)
        } finally {
            allowArchive.complete(Unit)
        }
    }

    @Test
    fun undoDoesNothingWhenPersistedArchiveIsNoLongerQueued() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(60_000) }
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val queuedJob = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        store.jobs.value = listOf(queuedJob.copy(state = WorktreeArchiveLifecycleState.REMOVING))

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            store.deleteQueuedJobCalls.first { it == listOf(DEV_LAKE_SELECTED_WORKTREE) }
        }
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")

        assertEquals(listOf(queuedJob), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(WorktreeArchiveLifecycleState.REMOVING, store.listJobs().single().state)
        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }
}

private fun archiveViewModel(
    api: RecordingGitWorktreeApi,
    store: RecordingWorktreeArchiveStore,
    archiveDelay: Duration = 60.seconds,
    waitForArchiveDeadline: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
    now: () -> Instant,
): EngHubViewModel = createLocalRepositoryViewModel(
    gitWorktreeApi = api,
    configWriter = RecordingEngHubConfigWriter(),
    localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
    services = LocalRepositoryViewModelServices(
        worktreeArchiveStore = store,
        archiveDelay = archiveDelay,
        waitForArchiveDeadline = waitForArchiveDeadline,
        archiveNow = now,
    ),
)

private fun archiveTestApi(
    secondPath: String? = null,
    onArchiveWorktree: (String, String, Boolean) -> Unit = { _, _, _ -> },
): RecordingGitWorktreeApi {
    val worktrees = buildList {
        add(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"))
        add(Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/login", commitHash = "def456"))
        secondPath?.let { path ->
            add(Worktree(path = path, branch = "feature/search", commitHash = "789abc"))
        }
    }
    return RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees),
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(onArchiveWorktree = onArchiveWorktree),
    )
}

private suspend fun expandRepository(viewModel: EngHubViewModel, expectedWorktreeCount: Int = 2) {
    viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
    withTimeout(2_000.milliseconds) {
        viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == expectedWorktreeCount }
    }
}
