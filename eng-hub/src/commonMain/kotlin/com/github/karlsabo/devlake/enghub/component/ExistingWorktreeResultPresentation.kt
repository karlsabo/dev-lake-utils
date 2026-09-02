package com.github.karlsabo.devlake.enghub.component

internal fun existingWorktreeResultKey(result: ExistingWorktreeResult): String = when (result) {
    is ExistingBranchWorktreeResult -> "branch:${result.repoRootPath}:${result.branch}"
    is ExistingPullRequestWorktreeResult -> "pr:${result.repoRootPath}:${result.number}:${result.branch}"
}

internal fun existingWorktreeResultLabel(result: ExistingWorktreeResult): String = when (result) {
    is ExistingBranchWorktreeResult ->
        "Branch · ${repositoryLabel(result.repoRootPath)} · ${result.branch}"

    is ExistingPullRequestWorktreeResult ->
        "PR #${result.number} · ${result.repositoryFullName} · ${result.branch}"
}

private fun repositoryLabel(repoRootPath: String): String = repoRootPath
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
