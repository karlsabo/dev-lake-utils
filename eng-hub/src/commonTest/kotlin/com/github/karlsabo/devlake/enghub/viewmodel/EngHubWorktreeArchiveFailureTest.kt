package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class EngHubWorktreeArchiveFailureTest {
    @Test
    fun genericFailurePersistsErrorAndRetryClaimsBeforeOrdinaryGitThenCompletes() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            val failed = fixture.failRemoval()
            assertEquals("permission denied", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            fixture.assertLeaseHeld()
            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            val retry = fixture.awaitAttempt()
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, retry.job.state)
            assertEquals(null, retry.job.errorMessage)
            assertEquals(true, retry.job.stateUpdatedAtEpochMs > failed.stateUpdatedAtEpochMs)
            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { fixture.store.deleteQueuedJobResults.first { it.isNotEmpty() } }
            assertEquals(listOf(false, false), fixture.api.archiveWorktreeForceValues)
            assertEquals(listOf(retry.job), fixture.store.listJobs())
            retry.result.complete(null)
            withTimeout(2_000.milliseconds) { fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryAfterGitRemovalAndRefreshFailureCompletesWithoutRemovingTwice() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            val first = fixture.startRemoval()
            fixture.failNextDiscovery = IllegalStateException("discovery unavailable")
            first.result.complete(null)
            val failed = fixture.awaitFailed()
            fixture.awaitError("Failed to complete worktree archive: discovery unavailable")
            assertEquals(1, fixture.gitRemovals.value)
            assertEquals(listOf(failed), fixture.store.listJobs())
            fixture.assertLeaseHeld()

            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            val retry = fixture.awaitAttempt()
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, retry.job.state)
            assertEquals(listOf(retry.job), fixture.store.listJobs())
            retry.result.complete(null)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(1, fixture.gitRemovals.value)
            assertEquals(listOf(false, false), fixture.api.archiveWorktreeForceValues)
            assertEquals(
                listOf(DEV_LAKE_ROOT),
                fixture.viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
            )
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dismissForgetsFailureAndDiscoversPartialRemovalWithoutClaimingUndo() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.failRemoval()
            fixture.discovered = fixture.worktrees.take(1)
            fixture.viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) {
                fixture.viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == 1 }
            }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dismissDiscoveryFailureStillForgetsJobAndReleasesLease() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.failRemoval()
            fixture.viewModel.clearActionError()
            fixture.discoveryFailure = IllegalStateException("discovery unavailable")
            fixture.viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            fixture.awaitError("Failed to refresh worktrees after dismissing archive: discovery unavailable")
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            fixture.viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failurePersistenceMustFinishBeforePublishingFailedAndKeepsLeaseOnWriteFailure() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        fixture.store.beforeFailedOperation = { operation, _ ->
            if (operation == "fail") {
                writeStarted.complete(Unit)
                runBlocking { allowWrite.await() }
                error("database unavailable")
            }
        }
        try {
            val attempt = fixture.startRemoval()
            attempt.result.complete(IllegalStateException("permission denied"))
            withTimeout(2_000.milliseconds) { writeStarted.await() }
            fixture.assertRemoving()
            allowWrite.complete(Unit)
            fixture.awaitError("Failed to persist worktree archive failure: database unavailable")
            fixture.assertRemoving()
            fixture.assertLeaseHeld()
        } finally {
            allowWrite.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun retryAndDismissPersistenceFailuresRetainFailedJobWithoutGitOrDiscovery() = runBlocking {
        listOf("retry", "dismiss").forEach { operation ->
            val fixture = ArchiveFailureFixture()
            try {
                val failed = fixture.failRemoval()
                fixture.viewModel.clearActionError()
                fixture.store.beforeFailedOperation = { _, _ -> error("database unavailable") }
                if (operation == "retry") {
                    fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
                } else {
                    fixture.viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
                }
                fixture.awaitError("Failed to $operation worktree archive: database unavailable")
                assertEquals(listOf(failed), fixture.store.listJobs())
                assertEquals(listOf(failed), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
                assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
                fixture.assertLeaseHeld()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun lateDismissAndDuplicateRetryCannotActOnNewFailureOfSameQueue() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val entered = Channel<String>(Channel.UNLIMITED)
        val allowStale = CompletableDeferred<Unit>()
        try {
            val original = fixture.failRemoval()
            fixture.store.beforeFailedOperation = { operation, _ ->
                if (operation != "fail") {
                    entered.trySend(operation)
                    runBlocking { allowStale.await() }
                }
            }
            fixture.viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { assertEquals("dismiss", entered.receive()) }
            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { assertEquals("retry", entered.receive()) }
            fixture.store.beforeFailedOperation = { _, _ -> }
            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            val retry = fixture.awaitAttempt()
            retry.result.complete(IllegalStateException("still denied"))
            val newFailure = fixture.awaitFailed()
            assertEquals(original.queueId, newFailure.queueId)
            assertEquals(true, newFailure.stateUpdatedAtEpochMs > original.stateUpdatedAtEpochMs)
            allowStale.complete(Unit)
            withTimeout(2_000.milliseconds) {
                fixture.store.failedOperationResults.first { results -> results.count { !it.second } == 2 }
            }
            assertEquals(listOf(newFailure), fixture.store.listJobs())
            assertEquals(listOf(newFailure), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false, false), fixture.api.archiveWorktreeForceValues)
            fixture.assertLeaseHeld()
        } finally {
            allowStale.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun staleFailureCannotOverwriteReplacementRecord() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            val attempt = fixture.startRemoval()
            val replacement = attempt.job.copy(queueId = "replacement")
            fixture.store.jobs.value = listOf(replacement)
            attempt.result.complete(IllegalStateException("permission denied"))
            fixture.awaitError("Failed to complete worktree archive: permission denied")
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(listOf(attempt.job), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            fixture.assertLeaseHeld()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringFailureWriteDoesNotPublishFailureOrError() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val entered = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        fixture.store.beforeFailedOperation = { _, _ ->
            entered.complete(Unit)
            runBlocking { allowWrite.await() }
        }
        try {
            val attempt = fixture.startRemoval()
            attempt.result.complete(IllegalStateException("permission denied"))
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.close()
            allowWrite.complete(Unit)
            withTimeout(2_000.milliseconds) { fixture.viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(WorktreeArchiveLifecycleState.FAILED, fixture.store.listJobs().single().state)
            assertEquals(
                WorktreeArchiveLifecycleState.REMOVING,
                fixture.viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
            )
            assertEquals(null, fixture.viewModel.actionErrorStateFlow.value)
        } finally {
            allowWrite.complete(Unit)
            fixture.close()
        }
    }
}

private data class ArchiveAttempt(
    val job: WorktreeArchiveJob,
    val result: CompletableDeferred<RuntimeException?>,
)

private class ArchiveFailureFixture {
    val store = RecordingWorktreeArchiveStore()
    val updateStarted = CompletableDeferred<Unit>()
    val worktrees = listOf(
        Worktree(DEV_LAKE_ROOT, "main", "abc"),
        Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/login", "def"),
    )
    var discovered = worktrees
    var discoveryFailure: RuntimeException? = null
    var failNextDiscovery: RuntimeException? = null
    val gitRemovals = MutableStateFlow(0)
    private val attempts = Channel<ArchiveAttempt>(Channel.UNLIMITED)
    private val deadline = CompletableDeferred<Unit>()
    private val releases = MutableStateFlow<List<CompletableDeferred<RuntimeException?>>>(emptyList())
    val api = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(worktreesForRepoPath = { discovered }),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onArchiveWorktree = { _, _, _ ->
                val result = CompletableDeferred<RuntimeException?>()
                releases.update { it + result }
                attempts.trySend(ArchiveAttempt(store.listJobs().single(), result))
                runBlocking { result.await() }?.let { throw it }
                if (discovered.any { it.path == DEV_LAKE_SELECTED_WORKTREE }) {
                    discovered = worktrees.take(1)
                    gitRemovals.update { it + 1 }
                }
            },
            onListWorktreeEntries = {
                failNextDiscovery?.let { failure ->
                    failNextDiscovery = null
                    throw failure
                }
                discoveryFailure?.let { throw it }
            },
            onUpdateWorktreeFromOrigin = { updateStarted.complete(Unit) },
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

    suspend fun startRemoval(): ArchiveAttempt {
        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == 2 }
        }
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() } }
        deadline.complete(Unit)
        return awaitAttempt()
    }

    suspend fun failRemoval(): WorktreeArchiveJob {
        startRemoval().result.complete(IllegalStateException("permission denied"))
        val failed = awaitFailed()
        awaitError("Failed to complete worktree archive: permission denied")
        return failed
    }

    suspend fun awaitAttempt(): ArchiveAttempt = withTimeout(2_000.milliseconds) { attempts.receive() }

    suspend fun awaitFailed(): WorktreeArchiveJob = withTimeout(2_000.milliseconds) {
        viewModel.queuedWorktreeArchivesStateFlow.first {
            it.singleOrNull()?.state == WorktreeArchiveLifecycleState.FAILED
        }.single()
    }

    suspend fun awaitError(message: String) {
        val error = withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.first { it?.message == message } }
        assertEquals(message, error?.message)
    }

    fun assertRemoving() {
        assertEquals(WorktreeArchiveLifecycleState.REMOVING, store.listJobs().single().state)
        assertEquals(
            WorktreeArchiveLifecycleState.REMOVING,
            viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
        )
    }

    fun assertLeaseHeld() {
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
    }

    fun close() {
        ViewModelStore().also { it.put("archive", viewModel) }.clear()
        releases.value.forEach { it.complete(IllegalStateException("closed")) }
    }
}
