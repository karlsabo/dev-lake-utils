package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.milliseconds

private val RECONCILIATION_RETRY_DELAY = 10.milliseconds

internal class LocalWorktreeArchiveCompletion(
    private val viewModel: ViewModel,
    private val state: EngHubViewModelState,
    private val localRepositories: LocalRepositoryController,
    private val store: WorktreeArchiveStore,
    private val clearEntry: (WorktreeArchiveJob) -> Unit,
) {
    private suspend fun reconcile(
        repoRootPath: String,
        checkActive: () -> Unit,
    ): List<LocalWorktreeUiState> {
        while (true) {
            checkActive()
            check(
                state.localRepositories.value.any {
                    it.path.normalizedRepositoryPath() == repoRootPath.normalizedRepositoryPath()
                },
            ) { "Repository is no longer configured: $repoRootPath" }
            localRepositories.refreshLocalRepositoryWorktrees(repoRootPath, checkActive)?.let { return it }
            // Supersession is not a discovery failure; retry without accepting stale rows.
            delay(RECONCILIATION_RETRY_DELAY)
        }
    }

    suspend fun dismiss(job: WorktreeArchiveJob) {
        val context = currentCoroutineContext()
        context.ensureActive()
        clearEntry(job)
        localRepositories.refreshLocalRepositoryWorktrees(job.repositoryRootPath) { context.ensureActive() }
    }

    suspend fun complete(job: WorktreeArchiveJob) {
        val completionContext = currentCoroutineContext()
        val checkActive = {
            completionContext.ensureActive()
            viewModel.viewModelScope.coroutineContext.ensureActive()
        }
        checkActive()
        val discovered = reconcile(job.repositoryRootPath, checkActive)
        check(discovered.none { it.path.normalizedRepositoryPath() == job.worktreePath }) {
            "Could not reconcile removed worktree: ${job.worktreePath}"
        }
        checkActive()
        if (store.deleteRemovingJob(job.worktreePath, job.queueId)) {
            checkActive()
            clearEntry(job)
            logger.info { "Completed worktree archive ${job.worktreePath}" }
        }
    }
}
