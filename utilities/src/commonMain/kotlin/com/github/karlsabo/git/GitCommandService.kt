package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions")
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

    override fun fetch(
        repoPath: String,
        remote: String,
        vararg refSpecs: String,
    ) {
        executeGitCommand(buildRepoCommand(repoPath, listOf("fetch", remote) + refSpecs.toList()))
    }

    override fun remoteBranchExists(
        repoPath: String,
        branch: String,
        remote: String,
    ): Boolean {
        val remoteBranchRef = "refs/heads/$branch"
        val command = buildRepoCommand(
            repoPath,
            "ls-remote",
            "--exit-code",
            "--heads",
            remote,
            remoteBranchRef,
        )
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        val result = executeCommand(command, workingDirectory = null)
        return when (result.exitCode) {
            0 -> true

            2 -> false

            else -> throw GitCommandException(
                command = command,
                exitCode = result.exitCode,
                gitOutput = result.stderr.ifEmpty { result.stdout },
            )
        }
    }

    override fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean {
        val command = buildRepoCommand(repoPath, "merge-base", "--is-ancestor", ancestorRef, descendantRef)
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        val result = executeCommand(command, workingDirectory = null)
        return when (result.exitCode) {
            0 -> true

            1 -> false

            else -> throw GitCommandException(
                command = command,
                exitCode = result.exitCode,
                gitOutput = result.stderr.ifEmpty { result.stdout },
            )
        }
    }

    override fun worktreeAdd(
        repoPath: String,
        path: String,
        commitIsh: String,
    ) {
        executeGitCommand(buildRepoCommand(repoPath, "worktree", "add", path, commitIsh))
    }

    override fun worktreeAddNewBranch(
        repoPath: String,
        newBranch: String,
        path: String,
        baseBranch: String,
    ) {
        executeGitCommand(buildRepoCommand(repoPath, "worktree", "add", "-b", newBranch, path, baseBranch))
    }

    override fun worktreeList(repoPath: String): String = executeGitCommand(
        buildRepoCommand(repoPath, "worktree", "list", "--porcelain"),
    )

    override fun worktreeRemove(repoPath: String, path: String) {
        executeGitCommand(buildRepoCommand(repoPath, "worktree", "remove", path))
    }

    override fun checkout(repoPath: String, ref: String) {
        executeGitCommand(buildRepoCommand(repoPath, "checkout", ref))
    }

    override fun status(repoPath: String): String = executeGitCommand(
        buildRepoCommand(repoPath, "status", "--porcelain"),
    )

    override fun log(repoPath: String, vararg args: String): String = executeGitCommand(
        buildRepoCommand(repoPath, listOf("log") + args.toList()),
    )

    override fun diff(repoPath: String, vararg args: String): String = executeGitCommand(
        buildRepoCommand(repoPath, listOf("diff") + args.toList()),
    )

    override fun revParse(repoPath: String, vararg args: String): String = executeGitCommand(
        buildRepoCommand(repoPath, listOf("rev-parse") + args.toList()),
    )

    override fun execute(repoPath: String?, vararg args: String): String {
        val command = if (repoPath != null) {
            buildRepoCommand(repoPath, args.toList())
        } else {
            listOf("git") + args.toList()
        }
        return executeGitCommand(command)
    }

    private fun buildRepoCommand(
        repoPath: String,
        vararg args: String,
    ): List<String> = buildRepoCommand(repoPath, args.toList())

    private fun buildRepoCommand(
        repoPath: String,
        args: List<String>,
    ): List<String> = listOf("git", "-C", repoPath) + args

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
