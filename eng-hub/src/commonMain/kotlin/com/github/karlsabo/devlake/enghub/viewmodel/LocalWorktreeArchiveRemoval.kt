package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class LocalWorktreeArchiveRemoval(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val gitWorktreeApi: GitWorktreeApi,
    private val archive: WorktreeArchiveDependencies,
    private val completion: LocalWorktreeArchiveCompletion,
    private val errorReporter: ActionErrorReporter,
) {

    suspend fun remove(job: WorktreeArchiveJob) {
        runCatching {
            currentCoroutineContext().ensureActive()
            gitWorktreeApi.archiveWorktree(job.repositoryRootPath, job.worktreePath, force = false)
            completion.complete(job)
        }.rethrowCancellation().onFailure { failure ->
            currentCoroutineContext().ensureActive()
            if (!failure.isDirtyWorktreeArchiveFailure()) retainFailure(job, failure)
            report("Failed to complete worktree archive", failure)
        }
    }

    fun retry(worktreePath: String) {
        val job = failedJob(worktreePath) ?: return
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val removing = job.copy(
                    state = WorktreeArchiveLifecycleState.REMOVING,
                    stateUpdatedAtEpochMs = nextStateTime(job),
                    errorMessage = null,
                )
                if (archive.store.transitionFailedJobToRemoving(job, removing.stateUpdatedAtEpochMs)) {
                    currentCoroutineContext().ensureActive()
                    publish(job, removing)
                    remove(removing)
                }
            }.rethrowCancellation().onFailure { report("Failed to retry worktree archive", it) }
        }
    }

    fun dismiss(worktreePath: String) {
        val job = failedJob(worktreePath) ?: return
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (archive.store.deleteFailedJob(job)) {
                    currentCoroutineContext().ensureActive()
                    reconcileAfterDismiss(job)
                }
            }.rethrowCancellation().onFailure { report("Failed to dismiss worktree archive", it) }
        }
    }

    private suspend fun reconcileAfterDismiss(job: WorktreeArchiveJob) {
        runCatching { completion.dismiss(job) }.rethrowCancellation().onFailure {
            report("Failed to refresh worktrees after dismissing archive", it)
        }
    }

    private suspend fun retainFailure(job: WorktreeArchiveJob, failure: Throwable) {
        val failed = job.copy(
            state = WorktreeArchiveLifecycleState.FAILED,
            stateUpdatedAtEpochMs = nextStateTime(job),
            errorMessage = failure.message?.takeIf { it.isNotBlank() } ?: failure.toString(),
        )
        runCatching {
            val persisted = archive.store.transitionRemovingJobToFailed(
                job,
                requireNotNull(failed.errorMessage),
                failed.stateUpdatedAtEpochMs,
            )
            if (persisted) {
                currentCoroutineContext().ensureActive()
                publish(job, failed)
            }
        }.rethrowCancellation().onFailure { report("Failed to persist worktree archive failure", it) }
    }

    private fun failedJob(worktreePath: String): WorktreeArchiveJob? = state.queuedWorktreeArchives.value.firstOrNull {
        it.worktreePath == worktreePath.normalizedRepositoryPath() && it.state == WorktreeArchiveLifecycleState.FAILED
    }

    // A monotonic attempt token prevents late Retry/Dismiss from acting on a later failure of the same queueId.
    private fun nextStateTime(job: WorktreeArchiveJob): Long = maxOf(
        archive.now().toEpochMilliseconds(),
        job.stateUpdatedAtEpochMs + 1,
    )

    private fun publish(previous: WorktreeArchiveJob, updated: WorktreeArchiveJob) {
        state.queuedWorktreeArchives.update { jobs -> jobs.map { if (it == previous) updated else it } }
    }

    private fun report(context: String, failure: Throwable) {
        viewModel.viewModelScope.coroutineContext.ensureActive()
        logger.error(failure) { context }
        errorReporter.enqueueActionError(failure.message?.let { "$context: $it" } ?: context)
    }
}
