package com.github.karlsabo.git

/**
 * Typed interface for common git CLI operations.
 *
 * All methods throw [GitCommandException] on non-zero exit codes unless
 * documented otherwise (e.g. [isGitRepository] returns a Boolean).
 */
interface GitCommandApi {
    /** Clone [url] into [targetPath]. */
    fun clone(url: String, targetPath: String)

    /** Return `true` when [repoPath] is inside a git work tree / bare repo. */
    fun isGitRepository(repoPath: String): Boolean

    /** `git -C <repoPath> fetch [remote] [refSpecs]`. */
    fun fetch(repoPath: String, remote: String = "origin", vararg refSpecs: String)

    /** `git -C <repoPath> worktree add <path> <commitIsh>`. */
    fun worktreeAdd(repoPath: String, path: String, commitIsh: String)

    /** `git -C <repoPath> worktree list --porcelain` — returns raw porcelain output. */
    fun worktreeList(repoPath: String): String

    /** `git -C <repoPath> worktree remove <path>`. */
    fun worktreeRemove(repoPath: String, path: String)

    /** `git -C <repoPath> checkout <ref>`. */
    fun checkout(repoPath: String, ref: String)

    /** `git -C <repoPath> status --porcelain` — returns raw output. */
    fun status(repoPath: String): String

    /** `git -C <repoPath> log <args>` — returns raw output. */
    fun log(repoPath: String, vararg args: String): String

    /** `git -C <repoPath> diff <args>` — returns raw output. */
    fun diff(repoPath: String, vararg args: String): String

    /** `git -C <repoPath> rev-parse <args>` — returns raw output. */
    fun revParse(repoPath: String, vararg args: String): String

    /**
     * Escape-hatch: run an arbitrary `git` command.
     *
     * @param repoPath working directory for `-C`; pass `null` to omit `-C`.
     * @param args     arguments appended after `git [-C repoPath]`.
     * @return the trimmed stdout of the command.
     */
    fun execute(repoPath: String? = null, vararg args: String): String
}
