package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class GitCommandService : GitCommandApi {

    override fun clone(url: String, targetPath: String) {
        executeGitCommand(listOf("git", "clone", url, targetPath))
    }

    override fun isGitRepository(repoPath: String): Boolean {
        val result = executeCommand(
            buildRepoCommand(repoPath, "rev-parse", "--git-dir"),
            workingDirectory = null,
        )
        return result.exitCode == 0
    }

    override fun fetch(repoPath: String, remote: String, vararg refSpecs: String) {
        val args = mutableListOf("fetch", remote)
        args.addAll(refSpecs)
        executeGitCommand(buildRepoCommand(repoPath, *args.toTypedArray()))
    }

    override fun worktreeAdd(repoPath: String, path: String, commitIsh: String) {
        executeGitCommand(buildRepoCommand(repoPath, "worktree", "add", path, commitIsh))
    }

    override fun worktreeList(repoPath: String): String {
        return executeGitCommand(buildRepoCommand(repoPath, "worktree", "list", "--porcelain"))
    }

    override fun worktreeRemove(repoPath: String, path: String) {
        executeGitCommand(buildRepoCommand(repoPath, "worktree", "remove", path))
    }

    override fun checkout(repoPath: String, ref: String) {
        executeGitCommand(buildRepoCommand(repoPath, "checkout", ref))
    }

    override fun status(repoPath: String): String {
        return executeGitCommand(buildRepoCommand(repoPath, "status", "--porcelain"))
    }

    override fun log(repoPath: String, vararg args: String): String {
        return executeGitCommand(buildRepoCommand(repoPath, "log", *args))
    }

    override fun diff(repoPath: String, vararg args: String): String {
        return executeGitCommand(buildRepoCommand(repoPath, "diff", *args))
    }

    override fun revParse(repoPath: String, vararg args: String): String {
        return executeGitCommand(buildRepoCommand(repoPath, "rev-parse", *args))
    }

    override fun execute(repoPath: String?, vararg args: String): String {
        val command = if (repoPath != null) {
            buildRepoCommand(repoPath, *args)
        } else {
            listOf("git") + args.toList()
        }
        return executeGitCommand(command)
    }

    private fun buildRepoCommand(repoPath: String, vararg args: String): List<String> {
        return listOf("git", "-C", repoPath) + args.toList()
    }

    private fun executeGitCommand(command: List<String>): String {
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        val result = executeCommand(command, workingDirectory = null)
        if (result.exitCode != 0) {
            val output = result.stderr.ifEmpty { result.stdout }
            throw GitCommandException(
                command = command,
                exitCode = result.exitCode,
                gitOutput = output,
            )
        }
        return result.stdout.trim()
    }
}
