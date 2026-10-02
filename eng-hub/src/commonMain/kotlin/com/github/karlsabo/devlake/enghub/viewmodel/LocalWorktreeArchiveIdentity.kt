package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun verifyRetryIdentity(
    gitWorktreeApi: GitWorktreeApi,
    job: WorktreeArchiveJob,
    removalAttempted: Boolean,
    checkoutPresent: (String) -> Boolean,
    pathPresent: (String) -> Boolean,
): Boolean {
    val worktrees = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
    currentCoroutineContext().ensureActive()
    val registration = worktrees.firstOrNull { it.path.normalizedRepositoryPath() == job.worktreePath }
    // An absent unregistered path is safe to finish via identity-checked resume even after restart.
    val absent = !pathPresent(job.worktreePath)
    val resumeAbsent = absent && (removalAttempted || registration == null)
    check(if (registration == null) absent else registration.branch == job.branch) {
        "Cannot retry archive: worktree registration or branch no longer matches: ${job.worktreePath}"
    }
    check(resumeAbsent || checkoutPresent(job.worktreePath)) {
        "Cannot retry archive: worktree checkout is missing: ${job.worktreePath}"
    }
    return resumeAbsent
}
