package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveStore
import kotlinx.coroutines.ensureActive

internal class LocalWorktreeArchiveCompletion(
    private val viewModel: ViewModel,
    private val localRepositories: LocalRepositoryController,
    private val store: WorktreeArchiveStore,
    private val clearEntry: (WorktreeArchiveJob) -> Unit,
) {
    fun complete(job: WorktreeArchiveJob) {
        val checkActive = { viewModel.viewModelScope.coroutineContext.ensureActive() }
        checkActive()
        val discovered = localRepositories.refreshLocalRepositoryWorktrees(job.repositoryRootPath, checkActive)
        check(discovered?.none { it.path.normalizedRepositoryPath() == job.worktreePath } == true) {
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
