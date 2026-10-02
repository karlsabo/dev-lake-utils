package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class EngHubWorktreeArchiveCompletionTest {
    @Test
    fun completionWaitsForReconciliationAndPersistenceBeforeReleasingLease() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        val deleteStarted = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        fixture.store.beforeDeleteRemovingJob = { _, _ ->
            fixture.assertOnlyRootDiscovered()
            deleteStarted.complete(Unit)
            runBlocking { allowDelete.await() }
        }
        try {
            fixture.startRemoval()
            fixture.assertRetained()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.assertRetained()
            fixture.allowRefresh.complete(Unit)
            withTimeout(2_000.milliseconds) { deleteStarted.await() }
            fixture.assertRetained()
            allowDelete.complete(Unit)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptyList(), fixture.store.listJobs())
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            assertEquals(listOf(true), fixture.store.deleteRemovingJobResults.value)
        } finally {
            allowDelete.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun archiveFailureRetainsNonCancelableJobWithoutRefreshingOrForcing() = runBlocking {
        val fixture = ArchiveCompletionFixture(archiveFailure = IllegalStateException("contains modified files"))
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitError("Failed to complete worktree archive: contains modified files")
            fixture.assertRetained(WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION)
            assertEquals(false, fixture.refreshStarted.isCompleted)
            assertEquals(emptyList(), fixture.store.deleteRemovingJobResults.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshFailureRetainsJobAndLease() = runBlocking {
        val fixture = ArchiveCompletionFixture(refreshFailure = IllegalStateException("discovery unavailable"))
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.allowRefresh.complete(Unit)
            fixture.awaitError("Failed to complete worktree archive: discovery unavailable")
            fixture.assertRetained(WorktreeArchiveLifecycleState.FAILED)
            assertEquals(emptyList(), fixture.store.deleteRemovingJobResults.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun persistedDeletionFailureRetainsJobAndLeaseAfterReconciliation() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        fixture.store.beforeDeleteRemovingJob = { _, _ -> error("database unavailable") }
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.allowRefresh.complete(Unit)
            fixture.awaitError("Failed to complete worktree archive: database unavailable")
            fixture.assertRetained(WorktreeArchiveLifecycleState.FAILED)
            fixture.assertOnlyRootDiscovered()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun discoveryStillContainingWorktreeCannotCompleteArchive() = runBlocking {
        val fixture = ArchiveCompletionFixture(retainDiscoveredWorktree = true)
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.allowRefresh.complete(Unit)
            fixture.awaitReconciliationError()
            fixture.assertRetained(WorktreeArchiveLifecycleState.FAILED)
            assertEquals(emptyList(), fixture.store.deleteRemovingJobResults.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun collapsedRepositoryRetriesFreshDiscoveryBeforeCompletingArchive() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
            fixture.allowRefresh.complete(Unit)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(listOf(true), fixture.store.deleteRemovingJobResults.value)
            assertEquals(null, fixture.viewModel.actionErrorStateFlow.value)
            fixture.assertOnlyRootDiscovered()
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun overlappingSuccessfulArchivesBothReconcileAndReleaseTheirLeases() = runBlocking {
        val secondPath = "$DEV_LAKE_ROOT-second"
        val fixture = ArchiveCompletionFixture(secondPath = secondPath)
        try {
            fixture.startRemoval()
            fixture.viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, secondPath)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.size == 2 }
            }
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            withTimeout(2_000.milliseconds) { fixture.secondRefreshStarted.await() }
            fixture.assertBothRetained()
            fixture.allowRefresh.complete(Unit)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(listOf(true, true), fixture.store.deleteRemovingJobResults.value)
            assertEquals(listOf(false, false), fixture.api.archiveWorktreeForceValues)
            assertEquals(null, fixture.viewModel.actionErrorStateFlow.value)
            fixture.assertOnlyRootDiscovered()
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, secondPath, "feature/second")
            withTimeout(2_000.milliseconds) { fixture.bothUpdatesStarted.await() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringGitRemovalDoesNotDeleteRecordOrPublishCompletion() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        try {
            fixture.startRemoval()
            fixture.close()
            withTimeout(2_000.milliseconds) { fixture.viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, fixture.store.listJobs().single().state)
            assertEquals(
                WorktreeArchiveLifecycleState.REMOVING,
                fixture.viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
            )
            assertEquals(false, fixture.refreshStarted.isCompleted)
            assertEquals(emptyList(), fixture.store.deleteRemovingJobResults.value)
            assertEquals(null, fixture.viewModel.actionErrorStateFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringDiscoveryDoesNotDeleteRecordOrPublishCompletion() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.close()
            withTimeout(2_000.milliseconds) { fixture.viewModel.viewModelScope.coroutineContext[Job]?.join() }
            fixture.assertRetained()
            assertEquals(emptyList(), fixture.store.deleteRemovingJobResults.value)
            assertEquals(null, fixture.viewModel.actionErrorStateFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleCompletionCannotDeleteReplacementRecordOrReleaseLease() = runBlocking {
        val fixture = ArchiveCompletionFixture()
        fixture.store.beforeDeleteRemovingJob = { _, _ ->
            fixture.store.jobs.value = fixture.store.jobs.value.map { it.copy(queueId = "replacement") }
        }
        try {
            fixture.startRemoval()
            fixture.allowArchive.complete(Unit)
            fixture.awaitRefresh()
            fixture.allowRefresh.complete(Unit)
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it.isNotEmpty() } }
            assertEquals(listOf(false), fixture.store.deleteRemovingJobResults.value)
            assertEquals("replacement", fixture.store.listJobs().single().queueId)
            fixture.assertRetained()
        } finally {
            fixture.close()
        }
    }
}

private class ArchiveCompletionFixture(
    private val archiveFailure: RuntimeException? = null,
    private val refreshFailure: RuntimeException? = null,
    private val retainDiscoveredWorktree: Boolean = false,
    secondPath: String? = null,
) {
    val store = RecordingWorktreeArchiveStore()
    val allowArchive = CompletableDeferred<Unit>()
    val allowRefresh = CompletableDeferred<Unit>()
    val refreshStarted = CompletableDeferred<Unit>()
    val updateStarted = CompletableDeferred<Unit>()
    val bothUpdatesStarted = CompletableDeferred<Unit>()
    val secondRefreshStarted = CompletableDeferred<Unit>()
    private val refreshCount = MutableStateFlow(0)
    private val updateCount = MutableStateFlow(0)
    private val deadline = CompletableDeferred<Unit>()
    private val archiveStarted = CompletableDeferred<Unit>()
    private val archived = MutableStateFlow(false)
    private val worktrees = listOf(
        Worktree(DEV_LAKE_ROOT, "main", "abc"),
        Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/login", "def"),
    ) + listOfNotNull(secondPath?.let { Worktree(it, "feature/second", "ghi") })
    val api = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesForRepoPath = {
                if (archived.value && !retainDiscoveredWorktree) worktrees.take(1) else worktrees
            },
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onArchiveWorktree = { _, _, _ ->
                archiveStarted.complete(Unit)
                runBlocking { allowArchive.await() }
                archiveFailure?.let { throw it }
                archived.value = true
            },
            onListWorktreeEntries = {
                if (archived.value) {
                    val count = refreshCount.updateAndGet { it + 1 }
                    if (count == 2) secondRefreshStarted.complete(Unit)
                    refreshStarted.complete(Unit)
                    runBlocking { allowRefresh.await() }
                    refreshFailure?.let { throw it }
                }
            },
            onUpdateWorktreeFromOrigin = {
                updateStarted.complete(Unit)
                if (updateCount.updateAndGet { it + 1 } == 2) bothUpdatesStarted.complete(Unit)
            },
        ),
    )
    val viewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        services = LocalRepositoryViewModelServices(
            worktreeArchiveStore = store,
            archiveNow = { Instant.fromEpochMilliseconds(10_000) },
            waitForArchiveDeadline = { deadline.await() },
        ),
    )

    suspend fun startRemoval() {
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == worktrees.size }
        }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() } }
        deadline.complete(Unit)
        withTimeout(2_000.milliseconds) { archiveStarted.await() }
    }

    suspend fun awaitRefresh() = withTimeout(2_000.milliseconds) { refreshStarted.await() }

    suspend fun awaitError(message: String) {
        val error = withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.first { it != null } }
        assertEquals(message, error?.message)
    }

    suspend fun awaitReconciliationError() {
        awaitError(
            "Failed to complete worktree archive: Could not reconcile removed worktree: $DEV_LAKE_SELECTED_WORKTREE",
        )
    }

    fun assertOnlyRootDiscovered() {
        assertEquals(
            listOf(DEV_LAKE_ROOT),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
    }

    fun assertBothRetained() {
        assertEquals(
            listOf(WorktreeArchiveLifecycleState.REMOVING, WorktreeArchiveLifecycleState.REMOVING),
            store.listJobs().map { it.state },
        )
        assertEquals(2, viewModel.queuedWorktreeArchivesStateFlow.value.size)
        assertEquals(emptyList(), store.deleteRemovingJobResults.value)
    }

    fun assertRetained(expectedState: WorktreeArchiveLifecycleState = WorktreeArchiveLifecycleState.REMOVING) {
        assertEquals(expectedState, store.listJobs().single().state)
        assertEquals(
            expectedState,
            viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
        )
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
    }

    fun close() {
        ViewModelStore().also { it.put("archive", viewModel) }.clear()
        allowArchive.complete(Unit)
        allowRefresh.complete(Unit)
    }
}
