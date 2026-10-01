package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

private const val DIRTY_REFUSAL = "worktree contains modified or untracked files"

class EngHubWorktreeArchiveForceTest {
    @Test
    fun dirtyRefusalRequiresExplicitConfirmationAndDismissCanReopenWithoutUndo() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val request = fixture.awaitConfirmation()
            val retained = fixture.store.listJobs().single()
            assertEquals(WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION, retained.state)
            assertEquals(DIRTY_REFUSAL, retained.errorMessage)
            assertEquals(null, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
            fixture.assertLeaseHeld()
            fixture.viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            withTimeout(2_000.milliseconds) { fixture.store.deleteQueuedJobResults.first { it.isNotEmpty() } }
            assertEquals(listOf(retained), fixture.store.listJobs())
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(request, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
            fixture.viewModel.dismissForceArchiveWorktreeRequest(request)
            assertEquals(null, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
            assertEquals(listOf(retained), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            val forced = fixture.awaitAttempt()
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, forced.job.state)
            assertEquals(null, forced.job.errorMessage)
            assertEquals(true, forced.job.stateUpdatedAtEpochMs > retained.stateUpdatedAtEpochMs)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            assertEquals(listOf(false, true), fixture.api.archiveWorktreeForceValues)
            forced.result.complete(null)
            withTimeout(2_000.milliseconds) { fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dirtyWriteFailureNeverPublishesConfirmationAndKeepsLease() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.beforeFailedOperation = { _, _ ->
            entered.complete(Unit)
            runBlocking { release.await() }
            error("database unavailable")
        }
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.assertRemoving()
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(null, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
            release.complete(Unit)
            fixture.awaitError("Failed to persist worktree archive failure: database unavailable")
            fixture.assertRemoving()
            fixture.assertLeaseHeld()
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun forceClaimWriteFailureRetainsConfirmationAndDoesNotInvokeGit() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val request = fixture.awaitConfirmation()
            fixture.viewModel.clearActionError()
            fixture.store.beforeFailedOperation = { _, _ -> error("database unavailable") }
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            fixture.awaitError("Failed to force worktree archive: database unavailable")
            assertEquals(
                WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
                fixture.store.listJobs().single().state,
            )
            assertEquals(fixture.store.listJobs(), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            fixture.assertLeaseHeld()
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            assertEquals(request, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleDialogCallbacksCannotConfirmOrDismissNewAttempt() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val original = fixture.awaitConfirmation()
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(original)
            fixture.awaitAttempt().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val current = fixture.awaitConfirmation()
            assertEquals(true, current.stateUpdatedAtEpochMs > original.stateUpdatedAtEpochMs)
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.dismissForceArchiveWorktreeRequest(original)
            fixture.viewModel.confirmForceArchiveLocalWorktree(original)
            assertEquals(current, fixture.viewModel.forceArchiveWorktreeRequestStateFlow.value)
            assertEquals(listOf(false, true), fixture.api.archiveWorktreeForceValues)
            fixture.assertLeaseHeld()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedForceAttemptRetainsFailureAndRetryRemainsOrdinary() = runBlocking {
        val fixture = ArchiveFailureFixture()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val request = fixture.awaitConfirmation()
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            fixture.viewModel.clearActionError()
            fixture.awaitAttempt().result.complete(IllegalStateException("permission denied"))
            val failed = fixture.awaitFailed()
            fixture.awaitError("Failed to complete worktree archive: permission denied")
            assertEquals(listOf(failed), fixture.store.listJobs())
            fixture.assertLeaseHeld()
            fixture.viewModel.retryFailedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
            val ordinaryRetry = fixture.awaitAttempt()
            assertEquals(listOf(false, true, false), fixture.api.archiveWorktreeForceValues)
            ordinaryRetry.result.complete(null)
            withTimeout(2_000.milliseconds) { fixture.viewModel.queuedWorktreeArchivesStateFlow.first { it.isEmpty() } }
            assertEquals(emptyList(), fixture.store.listJobs())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleForceClaimCannotRemoveReplacementQueue() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val request = fixture.awaitConfirmation()
            val retained = fixture.store.listJobs().single()
            fixture.store.beforeFailedOperation = { _, _ ->
                entered.complete(Unit)
                runBlocking { release.await() }
            }
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            withTimeout(2_000.milliseconds) { entered.await() }
            assertEquals(listOf(retained), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            val replacement = retained.copy(queueId = "replacement")
            fixture.store.jobs.value = listOf(replacement)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) {
                fixture.store.failedOperationResults.first { results -> results.any { it == ("force" to false) } }
            }
            assertEquals(listOf(replacement), fixture.store.listJobs())
            assertEquals(listOf(retained), fixture.viewModel.queuedWorktreeArchivesStateFlow.value)
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
            fixture.assertLeaseHeld()
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun disposalDuringForceClaimDoesNotPublishOrRunGit() = runBlocking {
        val fixture = ArchiveFailureFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.startRemoval().result.complete(IllegalStateException(DIRTY_REFUSAL))
            val request = fixture.awaitConfirmation()
            fixture.store.beforeFailedOperation = { _, _ ->
                entered.complete(Unit)
                runBlocking { release.await() }
            }
            fixture.viewModel.requestForceArchiveLocalWorktree(DEV_LAKE_SELECTED_WORKTREE)
            fixture.viewModel.confirmForceArchiveLocalWorktree(request)
            withTimeout(2_000.milliseconds) { entered.await() }
            fixture.close()
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { fixture.viewModel.viewModelScope.coroutineContext[Job]?.join() }
            assertEquals(WorktreeArchiveLifecycleState.REMOVING, fixture.store.listJobs().single().state)
            assertEquals(
                WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
                fixture.viewModel.queuedWorktreeArchivesStateFlow.value.single().state,
            )
            assertEquals(listOf(false), fixture.api.archiveWorktreeForceValues)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }
}

private suspend fun ArchiveFailureFixture.awaitConfirmation(): ForceArchiveWorktreeUiState {
    val job = withTimeout(2_000.milliseconds) {
        viewModel.queuedWorktreeArchivesStateFlow.first {
            it.singleOrNull()?.state == WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION
        }.single()
    }
    awaitError("Failed to complete worktree archive: $DIRTY_REFUSAL")
    assertNotNull(job.errorMessage)
    return ForceArchiveWorktreeUiState(job.repositoryRootPath, job.worktreePath, job.queueId, job.stateUpdatedAtEpochMs)
}
