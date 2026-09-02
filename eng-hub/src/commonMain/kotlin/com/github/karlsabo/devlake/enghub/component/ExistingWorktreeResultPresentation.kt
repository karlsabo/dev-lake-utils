package com.github.karlsabo.devlake.enghub.component

internal data class ExistingWorktreeResultIdentity(
    val type: ExistingWorktreeResultType,
    val repoRootPath: String,
    val branch: String,
    val pullRequestNumber: Int? = null,
)

internal enum class ExistingWorktreeResultType {
    BRANCH,
    PULL_REQUEST,
}

internal fun existingWorktreeResultIdentity(
    result: ExistingWorktreeResult,
): ExistingWorktreeResultIdentity = when (result) {
    is ExistingBranchWorktreeResult -> ExistingWorktreeResultIdentity(
        type = ExistingWorktreeResultType.BRANCH,
        repoRootPath = result.repoRootPath,
        branch = result.branch,
    )

    is ExistingPullRequestWorktreeResult -> ExistingWorktreeResultIdentity(
        type = ExistingWorktreeResultType.PULL_REQUEST,
        repoRootPath = result.repoRootPath,
        branch = result.branch,
        pullRequestNumber = result.number,
    )
}

internal fun selectedExistingWorktreeResult(
    selectedResult: ExistingWorktreeResult?,
    results: List<ExistingWorktreeResult>,
): ExistingWorktreeResult? = selectedResult?.let { selected ->
    results.firstOrNull { result -> sameExistingWorktreeResult(result, selected) }
}

internal fun sameExistingWorktreeResult(
    left: ExistingWorktreeResult?,
    right: ExistingWorktreeResult?,
): Boolean = left != null && right != null &&
    existingWorktreeResultIdentity(left) == existingWorktreeResultIdentity(right)

internal fun existingWorktreeResultKey(result: ExistingWorktreeResult): String {
    val identity = existingWorktreeResultIdentity(result)
    return with(identity) {
        listOf(type.name, repoRootPath, branch, pullRequestNumber.orEmptyKeyPart()).joinToString(":")
    }
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

private fun Int?.orEmptyKeyPart(): String = this?.toString().orEmpty()
