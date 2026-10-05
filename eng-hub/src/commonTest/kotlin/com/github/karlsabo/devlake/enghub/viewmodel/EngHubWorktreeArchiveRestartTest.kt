package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
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
            assertEquals(listOf(job), fixture.store.listJobs())
            viewModel.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            assertEquals(emptyList(), fixture.store.listJobs())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleRegistrationWithMissingCheckoutCannotScheduleDeletionOfReplacementFiles() = runBlocking {
        val directory = "/tmp/archive-stale-registration-${Random.nextLong().toULong().toString(16)}"
        val checkout = Path(directory, "checkout")
        val checkoutPath = checkout.toString().normalizedRepositoryPath()
        val unrelated = Path(checkout, "unrelated.txt")
        SystemFileSystem.createDirectories(checkout)
        writeText(unrelated, "keep me")
        val fixture = ArchiveRestartFixture(
            worktrees = listOf(Worktree(checkoutPath, "feature/login", "def")),
            checkoutPresent = ::worktreeCheckoutPresent,
            pathPresent = { SystemFileSystem.metadataOrNull(Path(it)) != null },
        )
        fixture.store.jobs.value = listOf(restartQueuedJob().copy(worktreePath = checkoutPath))
        try {
            val viewModel = fixture.start(100_000)
            val failed = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
            assertEquals("Queued worktree checkout is missing: $checkoutPath", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.clearActionError()
            viewModel.retryFailedWorktreeArchive(checkoutPath)
            fixture.awaitError(
                viewModel,
                "Failed to retry worktree archive: Cannot retry archive: worktree checkout is missing: $checkoutPath",
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
            val fixture = ArchiveRestartFixture(worktrees, pathPresent = { worktrees.isEmpty() })
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
    fun unregisteredReplacementDirectoryIsNeverDeletedOnResume() = runBlocking {
        val directory = "/tmp/archive-unregistered-${Random.nextLong().toULong().toString(16)}"
        val checkout = Path(directory, "checkout")
        val unrelated = Path(checkout, "new-file.txt")
        SystemFileSystem.createDirectories(checkout)
        writeText(unrelated, "keep me")
        val path = checkout.toString().normalizedRepositoryPath()
        val fixture = ArchiveRestartFixture(
            worktrees = emptyList(),
            pathPresent = { SystemFileSystem.metadataOrNull(Path(it)) != null },
        )
        fixture.store.jobs.value = listOf(
            restartQueuedJob().copy(worktreePath = path, state = WorktreeArchiveLifecycleState.REMOVING),
        )
        try {
            val viewModel = fixture.start(100_000)
            val failed = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
            assertEquals("Cannot resume archive: checkout identity cannot be verified: $path", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals("keep me", readText(unrelated))
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.undoQueuedWorktreeArchive(path)
            viewModel.clearActionError()
            viewModel.retryFailedWorktreeArchive(path)
            fixture.awaitError(
                viewModel,
                "Failed to retry worktree archive: Cannot retry archive: " +
                    "worktree registration or branch no longer matches: $path",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertEquals("keep me", readText(unrelated))
            viewModel.dismissFailedWorktreeArchive(path)
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals("keep me", readText(unrelated))
        } finally {
            fixture.close()
            removeTempDir(directory)
        }
    }

    @Test
    fun replacementAfterStartupIdentityCheckFailsRemovalAndRetainsEvidence() = runBlocking {
        val directory = "/tmp/archive-late-${Random.nextLong().toULong().toString(16)}"
        val path = Path(directory, "checkout").toString().normalizedRepositoryPath()
        val unrelated = Path(path, "new-file.txt")
        val fixture = ArchiveRestartFixture(
            worktrees = emptyList(),
            pathPresent = { SystemFileSystem.metadataOrNull(Path(it)) != null },
        )
        fixture.store.jobs.value = listOf(
            restartQueuedJob().copy(worktreePath = path, state = WorktreeArchiveLifecycleState.REMOVING),
        )
        fixture.onResume = { _, resumedPath, branch ->
            assertEquals(path, resumedPath)
            assertEquals("feature/login", branch)
            SystemFileSystem.createDirectories(Path(path))
            writeText(unrelated, "keep me")
            error("Cannot resume archive: checkout identity cannot be verified: $path")
        }
        try {
            val viewModel = fixture.start(100_000)
            val failed = withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first {
                    it.singleOrNull()?.state == WorktreeArchiveLifecycleState.FAILED
                }.single()
            }
            assertEquals("Cannot resume archive: checkout identity cannot be verified: $path", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals("keep me", readText(unrelated))
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
        } finally {
            fixture.close()
            if (SystemFileSystem.exists(Path(directory))) removeTempDir(directory)
        }
    }

    @Test
    fun replacementAppearingDuringDiscoveryCannotBeRemoved() = runBlocking {
        val directory = "/tmp/archive-discovery-${Random.nextLong().toULong().toString(16)}"
        val path = Path(directory, "checkout").toString().normalizedRepositoryPath()
        val unrelated = Path(path, "new-file.txt")
        val fixture = ArchiveRestartFixture(
            worktrees = emptyList(),
            pathPresent = { SystemFileSystem.metadataOrNull(Path(it)) != null },
        )
        fixture.store.jobs.value = listOf(
            restartQueuedJob().copy(worktreePath = path, state = WorktreeArchiveLifecycleState.REMOVING),
        )
        fixture.onDiscovery = {
            SystemFileSystem.createDirectories(Path(path))
            writeText(unrelated, "new data")
        }
        try {
            val viewModel = fixture.start(100_000)
            val failed = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
            assertEquals("Cannot resume archive: checkout identity cannot be verified: $path", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals("new data", readText(unrelated))
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
            removeTempDir(directory)
        }
    }
}

class EngHubWorktreeArchiveIdentityRestartTest {
    @Test
    fun identityFailureMustPersistBeforePublicationAndReleaseLeaseOnWriteFailure() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList(), pathPresent = { true })
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeFailedOperation = { operation, _ ->
            if (operation == "fail") {
                entered.complete(Unit)
                runBlocking { release.await() }
                error("disk full")
            }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { entered.await() }
            assertEquals(listOf(removing), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            release.complete(Unit)
            fixture.awaitError(viewModel, "Failed to restore queued worktree archive: disk full")
            assertEquals(listOf(removing), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun identityFailureCannotPublishOrChangeAReplacedAttempt() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList(), pathPresent = { true })
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        val replacement = removing.copy(queueId = "replacement")
        fixture.store.jobs.value = listOf(removing)
        fixture.store.beforeFailedOperation = { operation, _ ->
            if (operation == "fail") fixture.store.jobs.value = listOf(replacement)
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) {
                fixture.store.failedOperationResults.first { it.contains("fail" to false) }
            }
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringIdentityFailureWriteNeverPublishesOrRunsGit() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList(), pathPresent = { true })
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeFailedOperation = { operation, _ ->
            if (operation == "fail") {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.stop(viewModel)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(WorktreeArchiveLifecycleState.FAILED, fixture.store.listJobs().single().state)
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }
}

class EngHubWorktreeArchiveRemovalRestartTest {
    @Test
    fun blockedInterruptedRemovalDoesNotDelayQueuedRestorationAndUndo() = runBlocking {
        val fixture = ArchiveRestartFixture(
            worktrees = listOf(
                Worktree(DEV_LAKE_ROOT, "main", "abc"),
                Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/login", "def"),
                Worktree("/repos/queued", "feature/queued", "ghi"),
            ),
        )
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        val queued = restartQueuedJob().copy(
            worktreePath = "/repos/queued",
            branch = "feature/queued",
            queueId = "queued",
        )
        fixture.store.jobs.value = listOf(removing, queued)
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { fixture.archiveStarted.await() }
            val restored = withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first { jobs -> jobs.any { it.queueId == queued.queueId } }
            }
            assertEquals(
                WorktreeArchiveLifecycleState.REMOVING,
                restored.first { it.queueId == removing.queueId }.state,
            )
            val restoredQueued = restored.first { it.queueId == queued.queueId }
            assertEquals(WorktreeArchiveLifecycleState.QUEUED, restoredQueued.state)
            assertEquals(160_000, restoredQueued.deadlineAtEpochMs)
            viewModel.undoQueuedWorktreeArchive(queued.worktreePath)
            withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first { jobs -> jobs.none { it.queueId == queued.queueId } }
            }
            assertEquals(listOf(removing), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptedRemovalIsExposedWithoutUndoAndRetriesOrdinarilyBeforeAnyDeadline() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { fixture.archiveStarted.await() }
            assertEquals(listOf(removing), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(removing), fixture.store.listJobs())
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            assertEquals(emptyList(), fixture.store.transitionToRemovingCalls.value)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            assertEquals(1, fixture.api.archiveWorktreeCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptedRemovalFinishesAndDeletesPersistedRecord() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList())
        fixture.store.jobs.value = listOf(restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING))
        fixture.releaseArchive()
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it == listOf(true) } }
            assertEquals(emptyList(), fixture.store.listJobs())
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(1, fixture.api.archiveWorktreeCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun registeredAbsentCheckoutResumesAndClearsJobWithoutUndo() = runBlocking {
        val fixture = ArchiveRestartFixture(pathPresent = { false }, checkoutPresent = { false })
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        val resumed = CompletableDeferred<Unit>()
        fixture.onResume = { root, path, branch ->
            assertEquals(DEV_LAKE_ROOT, root)
            assertEquals(DEV_LAKE_SELECTED_WORKTREE, path)
            assertEquals("feature/login", branch)
            assertEquals(listOf(removing), fixture.store.listJobs())
            fixture.discovered = fixture.discovered.filterNot { it.path == path }
            resumed.complete(Unit)
        }
        fixture.releaseArchive()
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { resumed.await() }
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it == listOf(true) } }
            assertEquals(emptyList(), fixture.store.listJobs())
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun registeredAbsentCheckoutRetriesFailedPruneThroughIdentityCheckedResume() = runBlocking {
        val fixture = ArchiveRestartFixture(pathPresent = { false }, checkoutPresent = { false })
        fixture.store.jobs.value = listOf(restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING))
        var resumes = 0
        fixture.onResume = { _, path, _ ->
            resumes++
            if (resumes == 1) error("prune unavailable")
            fixture.discovered = fixture.discovered.filterNot { it.path == path }
        }
        fixture.releaseArchive()
        try {
            val viewModel = fixture.start(100_000)
            val failed = withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first {
                    it.singleOrNull()?.state == WorktreeArchiveLifecycleState.FAILED
                }.single()
            }
            assertEquals("prune unavailable", failed.errorMessage)
            assertEquals(listOf(failed), fixture.store.listJobs())
            viewModel.retryFailedWorktreeArchive(failed.worktreePath)
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it == listOf(true) } }
            assertEquals(2, resumes)
            assertEquals(emptyList(), fixture.store.listJobs())
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedRegisteredAbsentCheckoutRetriesAfterRestartWithoutDeletingReplacement() = runBlocking {
        val fixture = ArchiveRestartFixture(pathPresent = { false }, checkoutPresent = { false })
        fixture.store.jobs.value = listOf(restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING))
        var resumes = 0
        fixture.onResume = { _, path, branch ->
            assertEquals(DEV_LAKE_SELECTED_WORKTREE, path)
            assertEquals("feature/login", branch)
            resumes++
            if (resumes == 1) error("prune unavailable")
            fixture.discovered = fixture.discovered.filterNot { it.path == path }
        }
        fixture.releaseArchive()
        try {
            val first = fixture.start(100_000)
            val failed = withTimeout(2_000.milliseconds) {
                first.queuedWorktreeArchivesStateFlow.first {
                    it.singleOrNull()?.state == WorktreeArchiveLifecycleState.FAILED
                }.single()
            }
            assertEquals("prune unavailable", failed.errorMessage)
            fixture.stop(first)
            val restarted = fixture.start(200_000)
            assertEquals(failed, fixture.awaitRestored(restarted))
            restarted.retryFailedWorktreeArchive(failed.worktreePath)
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it == listOf(true) } }
            withTimeout(2_000.milliseconds) { restarted.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(2, resumes)
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptedRemovalFailureRetainsFailedOrForceConfirmationWithoutForce() = runBlocking {
        listOf(
            IllegalStateException("permission denied") to WorktreeArchiveLifecycleState.FAILED,
            IllegalStateException("worktree contains modified files") to
                WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
        ).forEach { (failure, expected) ->
            val fixture = ArchiveRestartFixture()
            fixture.store.jobs.value = listOf(restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING))
            fixture.archiveFailure = failure
            fixture.releaseArchive()
            try {
                val viewModel = fixture.start(100_000)
                val failed = withTimeout(2_000.milliseconds) {
                    viewModel.queuedWorktreeArchivesStateFlow.first { it.singleOrNull()?.state == expected }.single()
                }
                assertEquals(failure.message, failed.errorMessage)
                assertEquals(listOf(failed), fixture.store.listJobs())
                assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
                assertFalse(fixture.deadlines.tryReceive().isSuccess)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun staleRemovingSnapshotCannotRetryReplacementQueue() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        val replacement = removing.copy(queueId = "replacement")
        fixture.store.jobs.value = listOf(removing)
        fixture.onDiscovery = { fixture.store.jobs.value = listOf(replacement) }
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Removing worktree archive changed during startup: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringRemovingDiscoveryDoesNotPublishOrRunGit() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.onDiscovery = {
            entered.complete(Unit)
            runBlocking { release.await() }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.stop(viewModel)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(listOf(removing), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun removingReplacementBranchCannotBeDeleted() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = listOf(Worktree(DEV_LAKE_SELECTED_WORKTREE, "other", "def")))
        val removing = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.REMOVING)
        fixture.store.jobs.value = listOf(removing)
        try {
            val viewModel = fixture.start(100_000)
            val failed = fixture.awaitRestored(viewModel)
            assertEquals(WorktreeArchiveLifecycleState.FAILED, failed.state)
            assertEquals(
                "Cannot resume archive: worktree branch no longer matches: $DEV_LAKE_SELECTED_WORKTREE",
                failed.errorMessage,
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }
}

class EngHubWorktreeArchiveFailedRestartTest {
    @Test
    fun failedRestartKeepsErrorAndGuardWithoutAutomaticRetryThenDismisses() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        fixture.store.jobs.value = listOf(failed)
        try {
            val first = fixture.start(100_000)
            assertEquals(failed, fixture.awaitRestored(first))
            fixture.stop(first)
            val restarted = fixture.start(200_000)
            assertEquals(failed, fixture.awaitRestored(restarted))
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertEquals(emptyList(), fixture.api.listWorktreeEntryRepoPaths)
            restarted.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            restarted.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(listOf(failed), fixture.store.listJobs())
            restarted.dismissFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
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
    fun failedRestartRetryClaimsOrdinaryRemovalBeforeGit() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        fixture.store.jobs.value = listOf(failed)
        try {
            val viewModel = fixture.start(100_000)
            assertEquals(failed, fixture.awaitRestored(viewModel))
            viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { fixture.archiveStarted.await() }
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, fixture.store.listJobs().single().state)
            assertEquals(
                WorktreeArchiveLifecycleState.REMOVING,
                viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
            )
            assertEquals(listOf("retry" to true), fixture.store.failedOperationResults.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedRestartWithAbsentRegistrationAndPathRetriesCleanupWithoutDeletingReplacement() = runBlocking {
        val directory = "/tmp/archive-failed-retry-${Random.nextLong().toULong().toString(16)}"
        val path = Path(directory, "checkout").toString().normalizedRepositoryPath()
        val replacement = Path(path, "new-file.txt")
        val fixture = ArchiveRestartFixture(
            worktrees = emptyList(),
            checkoutPresent = { false },
            pathPresent = { SystemFileSystem.metadataOrNull(Path(it)) != null },
        )
        val failed = restartQueuedJob().copy(
            worktreePath = path,
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "prune unavailable",
        )
        fixture.store.jobs.value = listOf(failed)
        var resumed = 0
        fixture.onResume = { _, resumedPath, branch ->
            assertEquals(path, resumedPath)
            assertEquals("feature/login", branch)
            assertFalse(SystemFileSystem.exists(Path(path)))
            resumed++
            fixture.discovered = emptyList()
        }
        fixture.releaseArchive()
        try {
            val first = fixture.start(100_000)
            assertEquals(failed, fixture.awaitRestored(first))
            fixture.stop(first)
            val restarted = fixture.start(200_000)
            assertEquals(failed, fixture.awaitRestored(restarted))
            restarted.retryFailedWorktreeArchive(path)
            withTimeout(2_000.milliseconds) { fixture.store.deleteRemovingJobResults.first { it == listOf(true) } }
            withTimeout(2_000.milliseconds) { restarted.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(1, resumed)
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(listOf(DEV_LAKE_ROOT to path), fixture.api.archiveWorktreeCalls)

            // A new directory with no registration must still be rejected, not removed.
            fixture.store.jobs.value = listOf(failed.copy(queueId = "replacement-attempt"))
            SystemFileSystem.createDirectories(Path(path))
            writeText(replacement, "keep me")
            val next = fixture.start(300_000)
            val retained = fixture.awaitRestored(next)
            next.retryFailedWorktreeArchive(path)
            fixture.awaitError(
                next,
                "Failed to retry worktree archive: Cannot retry archive: " +
                    "worktree registration or branch no longer matches: $path",
            )
            assertEquals(listOf(retained), fixture.store.listJobs())
            assertEquals(1, resumed)
            assertEquals("keep me", readText(replacement))
        } finally {
            fixture.close()
            if (SystemFileSystem.exists(Path(directory))) removeTempDir(directory)
        }
    }

    @Test
    fun failedRegisteredCheckoutReplacedWithoutGitMarkerIsNotRemovedOnRetry() = runBlocking {
        val fixture = ArchiveRestartFixture(pathPresent = { true }, checkoutPresent = { false })
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "prune unavailable",
        )
        fixture.store.jobs.value = listOf(failed)
        try {
            val viewModel = fixture.start(100_000)
            assertEquals(failed, fixture.awaitRestored(viewModel))
            viewModel.retryFailedWorktreeArchive(failed.worktreePath)
            fixture.awaitError(
                viewModel,
                "Failed to retry worktree archive: Cannot retry archive: " +
                    "worktree checkout is missing: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedRestartConflictRetainsRecordWithoutPublishingActions() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        fixture.store.jobs.value = listOf(failed)
        val reading = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()
        fixture.store.beforeListJobs = {
            fixture.store.beforeListJobs = {}
            reading.complete(Unit)
            runBlocking { allowRead.await() }
        }
        fixture.onUpdate = { runBlocking { allowUpdate.await() } }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { reading.await() }
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            allowRead.complete(Unit)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Worktree mutation already in progress: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            allowRead.complete(Unit)
            allowUpdate.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun staleFailedStartupSnapshotDoesNotPublishReplacement() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        val replacement = failed.copy(queueId = "new-queue", errorMessage = "new failure")
        fixture.store.jobs.value = listOf(failed)
        fixture.store.beforeListJobs = {
            fixture.store.beforeListJobs = { fixture.store.jobs.value = listOf(replacement) }
        }
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Failed worktree archive changed during startup: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalDuringFailedRestorationDoesNotExposeActions() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        fixture.store.jobs.value = listOf(failed)
        val rechecking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeListJobs = {
            fixture.store.beforeListJobs = {
                rechecking.complete(Unit)
                runBlocking { release.await() }
            }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { rechecking.await() }
            fixture.stop(viewModel)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun invalidFailedIdentityRetainsPersistedErrorWithoutExposingActions() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val failed = restartQueuedJob().copy(
            worktreePath = DEV_LAKE_ROOT,
            state = WorktreeArchiveLifecycleState.FAILED,
            errorMessage = "permission denied",
        )
        fixture.store.jobs.value = listOf(failed)
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: Invalid failed worktree identity: $DEV_LAKE_ROOT",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedRecordsRestoreWhileForceConfirmationRemainsUntouched() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val deferred = WorktreeArchiveLifecycleState.entries.filter {
            it == WorktreeArchiveLifecycleState.FAILED || it == WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION
        }
            .map {
                restartQueuedJob().copy(
                    worktreePath = "/repos/${it.name.lowercase()}",
                    state = it,
                    errorMessage = "retained",
                )
            }
        fixture.store.jobs.value += deferred
        try {
            val viewModel = fixture.start(100_000)
            val published = withTimeout(2_000.milliseconds) {
                viewModel.queuedWorktreeArchivesStateFlow.first { jobs ->
                    jobs.any { it.queueId == "queue-login" && it.state == WorktreeArchiveLifecycleState.QUEUED } &&
                        deferred.first() in jobs
                }
            }
            val restored = published.single { it.worktreePath == DEV_LAKE_SELECTED_WORKTREE }
            assertEquals(160_000, restored.deadlineAtEpochMs)
            assertEquals(setOf(restored, deferred.first()), published.toSet())
            assertEquals(setOf(restored, *deferred.toTypedArray()), fixture.store.listJobs().toSet())
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
    private val pathPresent: (String) -> Boolean = { false },
) {
    val store = RecordingWorktreeArchiveStore().also { it.jobs.value = listOf(restartQueuedJob()) }
    val nowEpochMs = MutableStateFlow(100_000L)
    val deadlines = Channel<Pair<Duration, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    val updateStarted = CompletableDeferred<Unit>()
    var discovered = worktrees
    var onDiscovery: () -> Unit = {}
    var onUpdate: () -> Unit = {}
    var onResume: (String, String, String) -> Unit = { _, _, _ -> }
    val archiveStarted = CompletableDeferred<Unit>()
    private val allowArchive = CompletableDeferred<Unit>()
    var archiveFailure: RuntimeException? = null
    fun releaseArchive() {
        allowArchive.complete(Unit)
    }
    private val viewModels = mutableListOf<EngHubViewModel>()
    val api = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(worktreesForRepoPath = { discovered }),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onListWorktreeEntries = { onDiscovery() },
            onResumeArchiveWorktree = { root, path, branch -> onResume(root, path, branch) },
            onUpdateWorktreeFromOrigin = {
                updateStarted.complete(Unit)
                onUpdate()
            },
            onArchiveWorktree = { _, path, _ ->
                assertEquals(
                    WorktreeArchiveLifecycleState.REMOVING,
                    store.listJobs().first { it.worktreePath == path }.state,
                )
                archiveStarted.complete(Unit)
                runBlocking { allowArchive.await() }
                archiveFailure?.let { throw it }
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
                pathPresent = pathPresent,
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
