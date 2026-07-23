package com.github.karlsabo.git

import kotlin.jvm.JvmInline

@JvmInline
value class WorktreePath(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "worktreePath must not be blank" }
    }

    override fun toString(): String = value
}

fun buildWorktreePath(repoPath: String, branch: String): WorktreePath {
    require(repoPath.isNotBlank()) { "repoPath must not be blank" }
    require(branch.isNotBlank()) { "branch must not be blank" }

    val trimmedRepoPath = repoPath.trimEnd('/', '\\')
    val separatorIndex = maxOf(trimmedRepoPath.lastIndexOf('/'), trimmedRepoPath.lastIndexOf('\\'))
    val repoName = trimmedRepoPath.substring(separatorIndex + 1)
    val sanitized = sanitizeBranchName(branch)
    val parentDir = if (separatorIndex >= 0) trimmedRepoPath.substring(0, separatorIndex) else trimmedRepoPath
    val separator = if (separatorIndex >= 0) trimmedRepoPath[separatorIndex] else '/'
    return WorktreePath("$parentDir$separator$repoName-$sanitized")
}

fun sanitizeBranchName(branch: String): String = branch
    .replace(Regex("[^a-zA-Z0-9._-]"), "-")
    .trim('-')
