package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun verifyRetryIdentity(gitWorktreeApi: GitWorktreeApi, job: WorktreeArchiveJob) {
    val worktrees = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
    currentCoroutineContext().ensureActive()
    // A failed startup identity check must not authorize deletion of a replacement checkout or directory.
    check(
        worktrees.any {
            it.path.normalizedRepositoryPath() == job.worktreePath && it.branch == job.branch
        },
    ) { "Cannot retry archive: worktree registration or branch no longer matches: ${job.worktreePath}" }
}
