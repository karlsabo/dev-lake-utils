package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.Worktree
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class EngHubWorktreeArchiveForceRestartTest {
    @Test
    fun forceConfirmationSurvivesRestartWithoutModalOrGitUntilExplicitConfirmation() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val dirty = restartQueuedJob().copy(
            branch = "feature/wip",
            queueId = "dirty-queue",
            state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
            stateUpdatedAtEpochMs = 70_001,
            errorMessage = "worktree contains modified files",
        )
        fixture.store.jobs.value = listOf(dirty)
        fixture.discovered = listOf(Worktree(DEV_LAKE_SELECTED_WORKTREE, "feature/wip", "def"))
        try {
            val first = fixture.start(100_000)
            assertEquals(dirty, fixture.awaitRestored(first))
            fixture.stop(first)
            val restarted = fixture.start(200_000)
            assertEquals(dirty, fixture.awaitRestored(restarted))
            assertEquals(listOf(dirty), fixture.store.listJobs())
            assertEquals(null, restarted.forceArchiveWorktreeRequestStateFlow.value)
            assertFalse(fixture.deadlines.tryReceive().isSuccess)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            restarted.undoQueuedWorktreeArchive(dirty.worktreePath)
            restarted.retryFailedWorktreeArchive(dirty.worktreePath)
            restarted.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, dirty.worktreePath, dirty.branch)
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            restarted.requestForceArchiveLocalWorktree(dirty.worktreePath)
            val request = restarted.forceArchiveWorktreeRequestStateFlow.value
            assertEquals(dirty.queueId, request?.queueId)
            assertEquals(dirty.stateUpdatedAtEpochMs, request?.stateUpdatedAtEpochMs)
            restarted.dismissForceArchiveWorktreeRequest(requireNotNull(request))
            assertEquals(null, restarted.forceArchiveWorktreeRequestStateFlow.value)
            assertEquals(listOf(dirty), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            restarted.requestForceArchiveLocalWorktree(dirty.worktreePath)
            restarted.confirmForceArchiveLocalWorktree(request)
            withTimeout(2_000.milliseconds) { fixture.archiveStarted.await() }
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, fixture.store.listJobs().single().state)
            assertEquals(listOf(true), fixture.api.archiveWorktreeForceValues)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun restoredForceConfirmationRejectsChangedBranchWithoutClaimingRemoval() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val dirty = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
            errorMessage = "worktree contains modified files",
        )
        fixture.store.jobs.value = listOf(dirty)
        try {
            val viewModel = fixture.start(100_000)
            assertEquals(dirty, fixture.awaitRestored(viewModel))
            fixture.discovered = listOf(Worktree(dirty.worktreePath, "feature/replacement", "new"))
            viewModel.requestForceArchiveLocalWorktree(dirty.worktreePath)
            val request = requireNotNull(viewModel.forceArchiveWorktreeRequestStateFlow.value)
            viewModel.confirmForceArchiveLocalWorktree(request)
            fixture.awaitError(
                viewModel,
                "Failed to force worktree archive: Cannot force archive: " +
                    "worktree registration or checkout no longer matches: ${dirty.worktreePath}",
            )
            assertEquals(listOf(dirty), fixture.store.listJobs())
            assertEquals(listOf(dirty), viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun missingCheckoutAfterRestartCanBeDismissedWithoutForcedRemoval() = runBlocking {
        val fixture = ArchiveRestartFixture(worktrees = emptyList(), checkoutPresent = { false })
        val dirty = restartQueuedJob().copy(
            state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
            errorMessage = "worktree contains modified files",
        )
        fixture.store.jobs.value = listOf(dirty)
        try {
            val viewModel = fixture.start(100_000)
            assertEquals(dirty, fixture.awaitRestored(viewModel))
            viewModel.requestForceArchiveLocalWorktree(dirty.worktreePath)
            val request = requireNotNull(viewModel.forceArchiveWorktreeRequestStateFlow.value)
            viewModel.confirmForceArchiveLocalWorktree(request)
            fixture.awaitError(
                viewModel,
                "Failed to force worktree archive: Cannot force archive: " +
                    "worktree registration or checkout no longer matches: ${dirty.worktreePath}",
            )
            assertEquals(listOf(dirty), fixture.store.listJobs())
            viewModel.dismissFailedWorktreeArchive(dirty.worktreePath)
            withTimeout(2_000.milliseconds) { viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
            assertEquals(emptyList(), fixture.store.deleteQueuedJobCalls.value)
            assertEquals("dismiss-force" to true, fixture.store.failedOperationResults.value.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun forceConfirmationIsNotPublishedBeforeStartupRecheckAndHoldsLeaseAfterwards() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val dirty = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION)
        fixture.store.jobs.value = listOf(dirty)
        val checking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeListJobs = {
            fixture.store.beforeListJobs = {
                checking.complete(Unit)
                runBlocking { release.await() }
            }
        }
        try {
            val viewModel = fixture.start(100_000)
            withTimeout(2_000.milliseconds) { checking.await() }
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            viewModel.requestForceArchiveLocalWorktree(dirty.worktreePath)
            assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
            release.complete(Unit)
            assertEquals(dirty, fixture.awaitRestored(viewModel))
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, dirty.worktreePath, dirty.branch)
            assertEquals(emptyList(), fixture.api.updateWorktreeFromOriginCalls)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun forceConfirmationLeaseConflictRetainsEvidenceWithoutExposingReview() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val dirty = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION)
        fixture.store.jobs.value = listOf(dirty)
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
            viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, dirty.worktreePath, dirty.branch)
            withTimeout(2_000.milliseconds) { fixture.updateStarted.await() }
            allowRead.complete(Unit)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Worktree mutation already in progress: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(dirty), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            viewModel.requestForceArchiveLocalWorktree(dirty.worktreePath)
            assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            allowRead.complete(Unit)
            allowUpdate.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun staleForceConfirmationSnapshotNeverExposesReplacement() = runBlocking {
        val fixture = ArchiveRestartFixture()
        val dirty = restartQueuedJob().copy(state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION)
        val replacement = dirty.copy(queueId = "replacement")
        fixture.store.jobs.value = listOf(dirty)
        fixture.store.beforeListJobs = {
            fixture.store.beforeListJobs = { fixture.store.jobs.value = listOf(replacement) }
        }
        try {
            val viewModel = fixture.start(100_000)
            fixture.awaitError(
                viewModel,
                "Failed to restore queued worktree archive: " +
                    "Force-confirmation worktree archive changed during startup: $DEV_LAKE_SELECTED_WORKTREE",
            )
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
            viewModel.requestForceArchiveLocalWorktree(dirty.worktreePath)
            assertEquals(null, viewModel.forceArchiveWorktreeRequestStateFlow.value)
            assertEquals(emptyList(), fixture.api.archiveWorktreeCalls)
        } finally {
            fixture.close()
        }
    }
}
