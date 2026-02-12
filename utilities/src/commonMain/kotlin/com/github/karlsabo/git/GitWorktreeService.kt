package com.github.karlsabo.git

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

class GitWorktreeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class GitWorktreeService(
    private val gitCommandApi: GitCommandApi = GitCommandService(),
) : GitWorktreeApi {

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        if (!SystemFileSystem.exists(Path(repoPath))) {
            logger.info { "Repository not found at $repoPath, cloning from $cloneUrl" }
            try {
                gitCommandApi.clone(cloneUrl, repoPath)
            } catch (e: GitCommandException) {
                throw GitWorktreeException("Failed to clone $cloneUrl to $repoPath: ${e.gitOutput}", e)
            }
            logger.info { "Cloned $cloneUrl to $repoPath" }
        } else {
            if (!gitCommandApi.isGitRepository(repoPath)) {
                throw GitWorktreeException("Directory $repoPath exists but is not a git repository")
            }
            logger.info { "Repository already exists at $repoPath" }
        }
    }

    override fun ensureWorktree(repoPath: String, branch: String): String {
        val worktreePath = buildWorktreePath(repoPath, branch)

        val existing = listWorktrees(repoPath)
        if (existing.any { it.path == worktreePath }) {
            logger.info { "Worktree already exists at $worktreePath" }
            return worktreePath
        }

        try {
            gitCommandApi.fetch(repoPath, "origin", branch)
        } catch (e: GitCommandException) {
            logger.warn { "git fetch origin $branch failed (exitCode=${e.exitCode}): ${e.gitOutput}" }
        }

        try {
            gitCommandApi.worktreeAdd(repoPath, worktreePath, branch)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for branch $branch: ${e.gitOutput}",
                e
            )
        }

        logger.info { "Created worktree at $worktreePath for branch $branch" }
        return worktreePath
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean {
        val worktreePath = buildWorktreePath(repoPath, branch)
        return listWorktrees(repoPath).any { it.path == worktreePath }
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        val output = try {
            gitCommandApi.worktreeList(repoPath)
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to list worktrees for $repoPath: ${e.gitOutput}", e)
        }

        return parseWorktreeListPorcelain(output)
    }

    override fun removeWorktree(worktreePath: String) {
        try {
            gitCommandApi.execute(null, "worktree", "remove", worktreePath)
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to remove worktree at $worktreePath: ${e.gitOutput}", e)
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
