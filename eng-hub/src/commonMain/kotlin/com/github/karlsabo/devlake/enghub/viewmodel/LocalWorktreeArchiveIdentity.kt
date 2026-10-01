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
) {
    val worktrees = gitWorktreeApi.listWorktreeEntries(job.repositoryRootPath)
    currentCoroutineContext().ensureActive()
    val registration = worktrees.firstOrNull { it.path.normalizedRepositoryPath() == job.worktreePath }
    // Only an in-process removal attempt authorizes cleanup after registration has disappeared.
    // Startup identity failures and registered replacement branches still require a matching identity.
    check(if (registration == null) removalAttempted else registration.branch == job.branch) {
        "Cannot retry archive: worktree registration or branch no longer matches: ${job.worktreePath}"
    }
    check((removalAttempted && registration == null) || checkoutPresent(job.worktreePath)) {
        "Cannot retry archive: worktree checkout is missing: ${job.worktreePath}"
    }
}
