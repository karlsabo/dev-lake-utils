package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class GitWorktreeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class GitWorktreeService : GitWorktreeApi {

    override fun ensureWorktree(repoPath: String, branch: String): String {
        val worktreePath = buildWorktreePath(repoPath, branch)

        val existing = listWorktrees(repoPath)
        if (existing.any { it.path == worktreePath }) {
            logger.info { "Worktree already exists at $worktreePath" }
            return worktreePath
        }

        val fetchResult = executeCommand(
            listOf("git", "-C", repoPath, "fetch", "origin", branch),
            workingDirectory = null,
        )
        if (fetchResult.exitCode != 0) {
            logger.warn { "git fetch origin $branch failed (exitCode=${fetchResult.exitCode}): ${fetchResult.stderr.ifEmpty { fetchResult.stdout }}" }
        }

        val result = executeCommand(
            listOf("git", "-C", repoPath, "worktree", "add", worktreePath, branch),
            workingDirectory = null,
        )
        if (result.exitCode != 0) {
            val errorOutput = result.stderr.ifEmpty { result.stdout }
            throw GitWorktreeException("Failed to create worktree at $worktreePath for branch $branch: $errorOutput")
        }

        logger.info { "Created worktree at $worktreePath for branch $branch" }
        return worktreePath
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean {
        val worktreePath = buildWorktreePath(repoPath, branch)
        return listWorktrees(repoPath).any { it.path == worktreePath }
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        val result = executeCommand(
            listOf("git", "-C", repoPath, "worktree", "list", "--porcelain"),
            workingDirectory = null,
        )
        if (result.exitCode != 0) {
            val errorOutput = result.stderr.ifEmpty { result.stdout }
            throw GitWorktreeException("Failed to list worktrees for $repoPath: $errorOutput")
        }

        return parseWorktreeListPorcelain(result.stdout)
    }

    override fun removeWorktree(worktreePath: String) {
        val result = executeCommand(
            listOf("git", "worktree", "remove", worktreePath),
            workingDirectory = null,
        )
        if (result.exitCode != 0) {
            val errorOutput = result.stderr.ifEmpty { result.stdout }
            throw GitWorktreeException("Failed to remove worktree at $worktreePath: $errorOutput")
        }
        logger.info { "Removed worktree at $worktreePath" }
    }

    companion object {
        fun buildWorktreePath(repoPath: String, branch: String): String {
            val repoName = repoPath.trimEnd('/').substringAfterLast('/')
            val sanitized = sanitizeBranchName(branch)
            val parentDir = repoPath.trimEnd('/').substringBeforeLast('/')
            return "$parentDir/$repoName-$sanitized"
        }

        fun sanitizeBranchName(branch: String): String {
            return branch
                .replace(Regex("[^a-zA-Z0-9._-]"), "-")
                .trim('-')
        }

        internal fun parseWorktreeListPorcelain(output: String): List<Worktree> {
            val worktrees = mutableListOf<Worktree>()
            var path = ""
            var commitHash = ""
            var branch = ""

            for (line in output.lines()) {
                when {
                    line.startsWith("worktree ") -> path = line.removePrefix("worktree ")
                    line.startsWith("HEAD ") -> commitHash = line.removePrefix("HEAD ")
                    line.startsWith("branch ") -> branch = line.removePrefix("branch ").removePrefix("refs/heads/")
                    line.isBlank() && path.isNotEmpty() -> {
                        worktrees.add(Worktree(path = path, branch = branch, commitHash = commitHash))
                        path = ""
                        commitHash = ""
                        branch = ""
                    }
                }
            }

            if (path.isNotEmpty()) {
                worktrees.add(Worktree(path = path, branch = branch, commitHash = commitHash))
            }

            return worktrees
        }
    }
}
