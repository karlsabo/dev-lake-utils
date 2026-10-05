package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun verifyRetryIdentity(
    gitWorktreeApi: GitWorktreeApi,
    job: WorktreeArchiveJob,
    checkoutPresent: (String) -> Boolean,
    pathPresent: (String) -> Boolean,
): Boolean {
    val worktrees = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
    currentCoroutineContext().ensureActive()
    val registration = worktrees.firstOrNull { it.path.normalizedRepositoryPath() == job.worktreePath }
    // The persisted failed job may be a partial removal from a previous process.
    // Resume rechecks identity before pruning; never remove an unregistered checkout that exists.
    val absent = !pathPresent(job.worktreePath)
    check(if (registration == null) absent else registration.branch == job.branch) {
        "Cannot retry archive: worktree registration or branch no longer matches: ${job.worktreePath}"
    }
    check(absent || checkoutPresent(job.worktreePath)) {
        "Cannot retry archive: worktree checkout is missing: ${job.worktreePath}"
    }
    return absent
}
