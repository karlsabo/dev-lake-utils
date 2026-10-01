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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EngHubWorktreeArchiveRestartTest {
    @Test
    fun eachRestartPersistsFreshWindowHoldsLeaseAndAllowsUndoWithoutGit() = runBlocking {
        val fixture = ArchiveRestartFixture()
        try {
            val first = fixture.start(100_000)
            val firstJob = fixture.awaitRestored(first)
            assertEquals(160_000, firstJob.deadlineAtEpochMs)
            assertEquals(1_000, firstJob.queuedAtEpochMs)
            assertEquals("queue-login", firstJob.queueId)
            assertEquals(listOf(firstJob), fixture.store.listJobs())
            assertEquals(60.seconds, fixture.awaitDeadline().first)
            first.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            fixture.stop(first)

            val restarted = fixture.start(200_000)
            val restored = fixture.awaitRestored(restarted)
            assertEquals(260_000, restored.deadlineAtEpochMs)
            assertEquals(200_000, restored.stateUpdatedAtEpochMs)
            assertEquals(listOf(restored), fixture.store.listJobs())
            assertEquals(60.seconds, fixture.awaitDeadline().first)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            restarted.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { restarted.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
            restarted.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun slowDiscoveryStartsFreshWindowWhenRestoredJobIsExposed() = runBlocking {
        val fixture = ArchiveRestartFixture()
        fixture.onDiscovery = { fixture.nowEpochMs.value = 170_000 }
        try {
            val viewModel = fixture.start(100_000)
            val restored = fixture.awaitRestored(viewModel)
            assertEquals(170_000, restored.stateUpdatedAtEpochMs)
            assertEquals(230_000, restored.deadlineAtEpochMs)
            assertEquals(listOf(restored), fixture.store.listJobs())
            assertEquals(60.seconds, fixture.awaitDeadline().first)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun restoredDeadlineUsesOrdinaryRemovalOnlyAfterPersistedClaim() = runBlocking {
        val fixture = ArchiveRestartFixture()
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitRestored(viewModel)
            val deadline = fixture.awaitDeadline()
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            deadline.second.complete(Unit)
            withTimeout(2_000.milliseconds) { fixture.archiveStarted.await() }
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, fixture.store.listJobs().single().state)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            assertEquals(listOf(DEV_LAKE_SELECTED_WORKTREE), fixture.store.transitionToRemovingCalls.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun startupWriteMustFinishBeforePublicationAndFailureReleasesLease() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeRestoreQueuedJob = {
            entered.complete(Unit)
            runBlocking { release.await() }
            error("disk full")
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { entered.await() }
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(restartQueuedJob()), fixture.store.listJobs())
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            release.complete(Unit)
            fixture.awaitError(viewModel, "Failed to restore queued worktree archive: disk full")
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(restartQueuedJob()), fixture.store.listJobs())
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun staleStartupSnapshotCannotOverwriteReplacementOrExposeUndo() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val replacement = restartQueuedJob().copy(
            queueId = "replacement",
            state = WorktreeArchiveLifecycleState.REMOVING,
        )
        fixture.store.beforeRestoreQueuedJob = { fixture.store.jobs.value = listOf(replacement) }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) {
                fixture.store.failedOperationResults.first { it.contains("restore" to false) }
            }
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun missingWorktreeIsPersistedFailedWithoutUndoOrScheduling() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList())
        try {
            val viewModel = fixture.start(100_000)
            val job = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, job.state)
            assertEquals("Queued worktree is no longer registered: $DEV_LAKE_SELECTED_WORKTREE", job.errorMessage)
            assertEquals(listOf(job), fixture.store.listJobs())
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { fixture.store.deleteQueuedJobResults.first { it.isNotEmpty() } }
            assertEquals(listOf(job), fixture.store.listJobs())
            viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleRegistrationWithMissingCheckoutCannotScheduleDeletionOfReplacementFiles() = runBlocking {
        val directory = "/tmp/archive-stale-registration-${Random.nextLong().toULong().toString(16)}"
        val checkout = Path(directory, "checkout")
        val unrelated = Path(checkout, "unrelated.txt")
        SystemFileSystem.createDirectories(checkout)
        writeText(unrelated, "keep me")
        val fixture = ArchiveRestartFixture(
            worktrees = listOf(Worktree(checkout.toString(), "feature/login", "def")),
            checkoutPresent = ::worktreeCheckoutPresent,
        )
        fixture.store.jobs.value = listOf(restartQueuedJob().copy(worktreePath = checkout.toString()))
        try {
            val viewModel = fixture.start(100_000)
            val failed = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
            assertEquals("Queued worktree checkout is missing: $checkout", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.clearActionError()
            viewModel.retryFailedWorktreeArchive(checkout.toString())
            fixture.awaitError(
                viewModel,
                "Failed to retry worktree archive: Cannot retry archive: worktree checkout is missing: $checkout",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertEquals("keep me", readText(unrelated))
        } finally {
            fixture.close()
            removeTempDir(directory)
        }
    }

    @Test
    fun identityFailuresCannotRetryRemovalOfReplacementCheckoutOrUnregisteredDirectory() = runBlocking {
        val identities = listOf(
            emptyList(),
            listOf(Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/other", "def")),
        )
        identities.forEach { worktrees ->
            val fixture = ArchiveRestartFixture(worktrees)
            try {
                val viewModel = fixture.start(100_000)
                val failed = fixture.awaitRestored(viewModel)
                assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
                fixture.awaitError(viewModel, requireNotNull(failed.errorMessage))
                viewModel.clearActionError()
                viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
                fixture.awaitError(
                    viewModel,
                    "Failed to retry worktree archive: Cannot retry archive: " +
                        "worktree registration or branch no longer matches: $DEV_LAKE_SELECTED_WORKTREE",
                )
                assertEquals(listOf(failed), fixture.store.listJobs())
                assertEquals(listOf(failed), viewModel.queuedWorktreeArchivesStateFlow.value)
                assertEquals(listOf("restore" to true), fixture.store.failedOperationResults.value)
                assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
                assertFalse(fixture.deadlines.tryReceive().isSuccess)
                viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
                assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
                viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
                withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
                assertEquals(emptyList(), fixture.store.listJobs())
                assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun nonQueuedRecordsRemainUntouchedWhileQueuedRecordsRestore() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val deferred = WorktreeArchiveLifecycleState.entries.filterNot { it == WorktreeArchiveLifecycleState.QUEUED }
            .map { restartQueuedJob().copy(worktreePath = "/repos/${it.name}", state = it, errorMessage = "retained") }
        fixture.store.jobs.value += deferred
        try {
            val viewModel = fixture.start(100_000)
            val restored = fixture.awaitRestored(viewModel)
            assertEquals(deferred, fixture.store.listJobs().filterNot { it == restored })
            assertEquals(listOf(restored), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun discoveryFailureRetainsRecordWithoutClaimingUndo() = runBlocking {
        val fixture = ArchiveRestartFixture()
        fixture.onDiscovery = { error("discovery unavailable") }
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(viewModel, "Failed to restore queued worktree archive: discovery unavailable")
            assertEquals(listOf(restartQueuedJob()), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.store.failedOperationResults.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leaseConflictRetainsPersistedEvidenceAndReportsConflict() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val discovering = CompletableDeferred<Unit>()
        val allowDiscovery = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()
        fixture.onDiscovery = {
            discovering.complete(Unit)
            runBlocking { allowDiscovery.await() }
        }
        fixture.onUpdate = { runBlocking { allowUpdate.await() } }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { discovering.await() }
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            allowDiscovery.complete(Unit)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Worktree mutation already in progress: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(restartQueuedJob()), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.store.failedOperationResults.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            allowDiscovery.complete(Unit)
            allowUpdate.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun invalidRootIdentityCannotAcquireLeaseOrReachGit() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val invalid = restartQueuedJob().copy(worktreePath = DEV_LAKE_ROOT)
        fixture.store.jobs.value = listOf(invalid)
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: Invalid queued worktree identity: $DEV_LAKE_ROOT",
            )
            assertEquals(listOf(invalid), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.listWorktreeEntryRepoPaths)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun loadFailureReportsErrorWithoutGitOrPublication() = runBlocking {
        val fixture = ArchiveRestartFixture()
        fixture.store.listFailure = IllegalStateException("database unavailable")
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(viewModel, "Failed to restore queued worktree archive: database unavailable")
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(restartQueuedJob()), fixture.store.jobs.value)
            assertEquals(emptyList(), fixture.api.listWorktreeEntryRepoPaths)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringStartupWriteNeverPublishesOrSchedulesRemoval() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeRestoreQueuedJob = {
            entered.complete(Unit)
            runBlocking { release.await() }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.stop(viewModel)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(160_000, fixture.store.listJobs().single().deadlineAtEpochMs)
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(null, viewModel.actionErrorStateFlow.value)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }
}

private fun restartQueuedJob() = WorktreeArchiveJob(
    repositoryRootPath = DEV_LAKE_ROOT,
    worktreePath = DEV_LAKE_SELECTED_WORKTREE,
    branch = "feature/login",
    queueId = "queue-login",
    state = WorktreeArchiveLifecycleState.QUEUED,
    queuedAtEpochMs = 1_000,
    stateUpdatedAtEpochMs = 1_000,
    deadlineAtEpochMs = 61_000,
)

private class ArchiveRestartFixture(
    worktrees: List<Worktree> = listOf(
        Worktree(DEV_LAKE_ROOT, "main", "abc"),
        Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/login", "def"),
    ),
    private val checkoutPresent: (String) -> Boolean = { true },
) {
    val store = RecordingWorktreeArchiveStore().also { it.jobs.value = listOf(restartQueuedJob()) }
    val nowEpochMs = MutableStateFlow(100_000L)
    val deadlines = Channel<Pair<Duration, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    val updateStarted = CompletableDeferred<Unit>()
    var onDiscovery: () -> Unit = {}
    var onUpdate: () -> Unit = {}
    val archiveStarted = CompletableDeferred<Unit>()
    private val allowArchive = CompletableDeferred<Unit>()
    private val viewModels = mutableListOf<EngHubViewModel>()
    val api = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees)),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onListWorktreeEntries = { onDiscovery() },
            onUpdateWorktreeFromOrigin = {
                updateStarted.complete(Unit)
                onUpdate()
            },
            onArchiveWorktree = { _, _, _ ->
                assertEquals(WorktreeArchiveLifecycleState.REMOVING, store.listJobs().single().state)
                archiveStarted.complete(Unit)
                runBlocking { allowArchive.await() }
            },
        ),
    )

    fun start(now: Long): EngHubViewModel {
        nowEpochMs.value = now
        return createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            services = LocalRepositoryViewModelServices(
                worktreeArchiveStore = store,
                archiveNow = { Instant.fromEpochMilliseconds(nowEpochMs.value) },
                checkoutPresent = checkoutPresent,
                waitForArchiveDeadline = { duration ->
                    val release = CompletableDeferred<Unit>()
                    deadlines.send(duration to release)
                    release.await()
                },
            ),
        ).also { viewModels += it }
    }

    suspend fun awaitRestored(viewModel: EngHubViewModel): WorktreeArchiveJob = withTimeout(2_000.milliseconds) {
        viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
    }

    suspend fun awaitDeadline() = withTimeout(2_000.milliseconds) { deadlines.receive() }

    suspend fun awaitError(viewModel: EngHubViewModel, message: String) {
        withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.first { it?.message == message } }
    }

    fun stop(viewModel: EngHubViewModel) {
        ViewModelStore().also { it.put("archive", viewModel) }.clear()
    }

    fun close() {
        viewModels.forEach(::stop)
        allowArchive.complete(Unit)
    }
}
