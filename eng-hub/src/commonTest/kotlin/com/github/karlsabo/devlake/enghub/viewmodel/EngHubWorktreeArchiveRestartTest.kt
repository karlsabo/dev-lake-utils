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
        val checkoutPath = checkout.toString().normalizedRepositoryPath()
        val unrelated = Path(checkout, "unrelated.txt")
        SystemFileSystem.createDirectories(checkout)
        writeText(unrelated, "keep me")
        val fixture = ArchiveRestartFixture(
            worktrees = listOf(Worktree(checkoutPath, "feature/login", "def")),
            checkoutPresent = ::worktreeCheckoutPresent,
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
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            viewModel.clearActionError()
            viewModel.retryFailedWorktreeArchive(path)
            fixture.awaitError(
                viewModel,
                "Failed to retry worktree archive: Cannot retry archive: " +
                    "worktree registration or branch no longer matches: $path",
            )
            assertEquals(listOf(failed), fixture.store.listJobs())
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
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(1, fixture.api.archiveWorktreeCalls.size)
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

    @Test
    fun nonQueuedRecordsRemainUntouchedWhileQueuedRecordsRestore() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val deferred = WorktreeArchiveLifecycleState.entries.filter {
            it == WorktreeArchiveLifecycleState.FAILED || it == WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION
        }
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
    private val pathPresent: (String) -> Boolean = { false },
) {
    val store = RecordingWorktreeArchiveStore().also { it.jobs.value = listOf(restartQueuedJob()) }
    val nowEpochMs = MutableStateFlow(100_000L)
    val deadlines = Channel<Pair<Duration, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    val updateStarted = CompletableDeferred<Unit>()
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
        responses = RecordingGitWorktreeApiResponses(worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees)),
        callbacks = RecordingGitWorktreeApiCallbacks(
            onListWorktreeEntries = { onDiscovery() },
            onResumeArchiveWorktree = { root, path, branch -> onResume(root, path, branch) },
            onUpdateWorktreeFromOrigin = {
                updateStarted.complete(Unit)
                onUpdate()
            },
            onArchiveWorktree = { _, _, _ ->
                assertEquals(
                    WorktreeArchiveLifecycleState.REMOVING,
                    store.listJobs().first { it.worktreePath == DEV_LAKE_SELECTED_WORKTREE }.state,
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
