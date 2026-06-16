package com.github.karlsabo.git

import com.github.karlsabo.system.ProcessResult
import com.github.karlsabo.system.executeCommand
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class GitCommandService : GitCommandApi by DefaultGitCommandApi(GitCliCommandRunner())

private class DefaultGitCommandApi(
    commandRunner: GitCliCommandRunner,
) : GitCommandApi,
    GitRepositoryCommandApi by GitRepositoryCommandService(commandRunner),
    GitRemoteCommandApi by GitRemoteCommandService(commandRunner),
    GitBranchCommandApi by GitBranchCommandService(commandRunner),
    GitAncestryCommandApi by GitAncestryCommandService(commandRunner),
    GitWorktreeCommandApi by GitWorktreeCommandService(commandRunner),
    GitRebaseCommandApi by GitRebaseCommandService(commandRunner),
    GitWorkingTreeCommandApi by GitWorkingTreeCommandService(commandRunner),
    GitHistoryCommandApi by GitHistoryCommandService(commandRunner),
    GitRawCommandExecutor by GitRawCommandService(commandRunner)

private class GitCliCommandRunner {
    fun run(command: List<String>): String {
        val result = runForResult(command)
        if (result.exitCode != 0) {
            throw GitCommandException(
                command = command,
                exitCode = result.exitCode,
                gitOutput = result.stderr.ifEmpty { result.stdout },
            )
        }
        return result.stdout.trim()
    }

    fun runForResult(command: List<String>): ProcessResult {
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        return executeCommand(command, workingDirectory = null)
    }
}

private class GitRepositoryCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitRepositoryCommandApi {
    override fun clone(url: String, targetPath: String) {
        commandRunner.run(listOf("git", "clone", url, targetPath))
    }

    override fun isGitRepository(repoPath: String): Boolean {
        val result = commandRunner.runForResult(gitRepoCommand(repoPath, "rev-parse", "--git-dir"))
        return result.exitCode == 0
    }

    override fun checkout(repoPath: String, ref: String) {
        commandRunner.run(gitRepoCommand(repoPath, "checkout", ref))
    }
}

private class GitRemoteCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitRemoteCommandApi {
    override fun fetch(
        repoPath: String,
        remote: String,
        vararg refSpecs: String,
    ) {
        commandRunner.run(gitRepoCommand(repoPath, listOf("fetch", remote) + refSpecs.toList()))
    }

    override fun remoteBranchExists(
        repoPath: String,
        branch: String,
        remote: String,
    ): Boolean {
        val command = gitRepoCommand(
            repoPath,
            "ls-remote",
            "--exit-code",
            "--heads",
            remote,
            "refs/heads/$branch",
        )
        val result = commandRunner.runForResult(command)
        return when (result.exitCode) {
            0 -> true
            2 -> false
            else -> throwGitCommandException(command, result)
        }
    }

    override fun currentBranchUpstreamRemote(repoPath: String): String? {
        val currentBranch = commandRunner.run(gitRepoCommand(repoPath, "branch", "--show-current"))
            .takeIf { it.isNotBlank() }
            ?: return null
        val command = gitRepoCommand(repoPath, "config", "--get", "branch.$currentBranch.remote")
        val result = commandRunner.runForResult(command)
        return when (result.exitCode) {
            0 -> result.stdout.trim().takeIf { it.isNotBlank() }
            1 -> null
            else -> throwGitCommandException(command, result)
        }
    }

    override fun remoteDefaultBranchRef(
        repoPath: String,
        remote: String,
    ): String? {
        require(remote.isNotBlank()) { "remote must not be blank" }
        val command = gitRepoCommand(repoPath, "rev-parse", "--verify", "--quiet", "refs/remotes/$remote/HEAD")
        val result = commandRunner.runForResult(command)
        return when (result.exitCode) {
            0 -> "$remote/HEAD"
            1 -> null
            else -> throwGitCommandException(command, result)
        }
    }
}

private class GitBranchCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitBranchCommandApi {
    override fun localBranchExists(
        repoPath: String,
        branch: String,
    ): Boolean {
        val command = gitRepoCommand(repoPath, "show-ref", "--verify", "--quiet", "refs/heads/$branch")
        val result = commandRunner.runForResult(command)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throwGitCommandException(command, result)
        }
    }
}

private class GitAncestryCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitAncestryCommandApi {
    override fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean {
        val command = gitRepoCommand(repoPath, "merge-base", "--is-ancestor", ancestorRef, descendantRef)
        val result = commandRunner.runForResult(command)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throwGitCommandException(command, result)
        }
    }

    override fun hasCommitsNotContainedIn(
        repoPath: String,
        sourceRef: String,
        containingRef: String,
    ): Boolean = commandRunner.run(
        gitRepoCommand(repoPath, "rev-list", "--max-count=1", "$containingRef..$sourceRef"),
    ).isNotBlank()
}

private class GitWorktreeCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitWorktreeCommandApi {
    override fun worktreeAdd(
        repoPath: String,
        path: String,
        commitIsh: String,
    ) {
        commandRunner.run(gitRepoCommand(repoPath, "worktree", "add", path, commitIsh))
    }

    override fun worktreeAddNewBranch(
        repoPath: String,
        newBranch: String,
        path: String,
        baseCommitIsh: String,
    ) {
        commandRunner.run(gitRepoCommand(repoPath, "worktree", "add", "-b", newBranch, path, baseCommitIsh))
    }

    override fun worktreeList(repoPath: String): String = commandRunner.run(
        gitRepoCommand(repoPath, "worktree", "list", "--porcelain"),
    )

    override fun worktreeRemove(repoPath: String, path: String) {
        commandRunner.run(gitRepoCommand(repoPath, "worktree", "remove", path))
    }
}

private class GitRebaseCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitRebaseCommandApi {
    override fun rebase(repoPath: String, upstreamRef: String) {
        commandRunner.run(gitRepoCommand(repoPath, "rebase", "--autostash", upstreamRef))
    }
}

private class GitWorkingTreeCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitWorkingTreeCommandApi {
    override fun status(repoPath: String): String = commandRunner.run(
        gitRepoCommand(repoPath, "status", "--porcelain"),
    )
}

private class GitHistoryCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitHistoryCommandApi {
    override fun log(repoPath: String, vararg args: String): String = commandRunner.run(
        gitRepoCommand(repoPath, listOf("log") + args.toList()),
    )

    override fun diff(repoPath: String, vararg args: String): String = commandRunner.run(
        gitRepoCommand(repoPath, listOf("diff") + args.toList()),
    )

    override fun revParse(repoPath: String, vararg args: String): String = commandRunner.run(
        gitRepoCommand(repoPath, listOf("rev-parse") + args.toList()),
    )
}

private class GitRawCommandService(
    private val commandRunner: GitCliCommandRunner,
) : GitRawCommandExecutor {
    override fun execute(repoPath: String?, vararg args: String): String {
        val command = if (repoPath == null) {
            listOf("git") + args.toList()
        } else {
            gitRepoCommand(repoPath, args.toList())
        }
        return commandRunner.run(command)
    }
}

private fun gitRepoCommand(
    repoPath: String,
    vararg args: String,
): List<String> = gitRepoCommand(repoPath, args.toList())

private fun gitRepoCommand(
    repoPath: String,
    args: List<String>,
): List<String> = listOf("git", "-C", repoPath) + args

private fun throwGitCommandException(
    command: List<String>,
    result: ProcessResult,
): Nothing = throw GitCommandException(
    command = command,
    exitCode = result.exitCode,
    gitOutput = result.stderr.ifEmpty { result.stdout },
)
