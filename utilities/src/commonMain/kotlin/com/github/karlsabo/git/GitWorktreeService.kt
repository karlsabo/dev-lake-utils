package com.github.karlsabo.git

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

class GitWorktreeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class GitWorktreeService(
    private val gitCommandApi: GitCommandApi = GitCommandService(),
    private val deleteCheckoutDirectory: (String) -> Unit = ::deleteCheckoutDirectory,
    private val branchNameValidator: WorktreeBranchNameValidator = WorktreeBranchNameValidator { branch ->
        gitCommandApi.isValidBranchRefFormat(branch)
    },
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
        validateWorktreeBranchName(branch)
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

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String {
        require(baseWorktreePath.isNotBlank()) { "baseWorktreePath must not be blank" }
        validateWorktreeBranchName(baseBranch)
        validateWorktreeBranchName(targetBranch)
        val worktreePath = buildWorktreePath(repoPath, targetBranch)
        val checkedOutElsewhere = listWorktreeEntries(repoPath).firstOrNull { worktree ->
            worktree.branch == targetBranch && worktree.path != worktreePath
        }
        if (checkedOutElsewhere != null) {
            throw GitWorktreeException(
                "Branch $targetBranch is already checked out elsewhere at ${checkedOutElsewhere.path}. " +
                        "Choose a different branch name.",
            )
        }

        try {
            gitCommandApi.worktreeAddNewBranch(baseWorktreePath, targetBranch, worktreePath, baseBranch)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for branch $targetBranch from $baseBranch: ${e.gitOutput}",
                e,
            )
        }

        logger.info { "Created worktree at $worktreePath for branch $targetBranch from $baseBranch" }
        return worktreePath
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean {
        val worktreePath = buildWorktreePath(repoPath, branch)
        return listWorktrees(repoPath).any { it.path == worktreePath }
    }

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        if (!gitCommandApi.isGitRepository(selectedPath)) {
            throw GitWorktreeException("Directory $selectedPath is not a git repository")
        }

        val selectedWorktreePath = try {
            gitCommandApi.revParse(selectedPath, "--show-toplevel")
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Could not resolve a worktree root for $selectedPath. Please add the repository root directory.",
                e,
            )
        }
        val worktrees = listWorktrees(selectedWorktreePath)
        val rootPath = worktrees.firstOrNull()?.path
            ?: throw GitWorktreeException("No git worktrees found for $selectedPath")

        if (worktrees.none { it.path == selectedWorktreePath }) {
            throw GitWorktreeException(
                "Could not match $selectedPath to a git worktree. Please add the repository root directory.",
            )
        }

        return RepositoryWorktrees(
            rootPath = rootPath,
            selectedWorktreePath = selectedWorktreePath,
            worktrees = worktrees,
        )
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        return listWorktreeEntries(repoPath).map { worktree ->
            worktree.copy(isDirty = isWorktreeDirty(worktree.path))
        }
    }

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        removeWorktree(worktreePath) {
            if (force) {
                gitCommandApi.execute(null, "worktree", "remove", "--force", worktreePath)
            } else {
                gitCommandApi.execute(null, "worktree", "remove", worktreePath)
            }
        }
    }

    override fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean) {
        removeWorktree(repoPath, worktreePath, force)
        val deleteFailure = runCatching { deleteCheckoutDirectory(worktreePath) }.exceptionOrNull()
        val pruneFailure = runCatching { pruneWorktrees(repoPath) }.exceptionOrNull()

        if (deleteFailure != null) {
            if (pruneFailure != null) deleteFailure.addSuppressed(pruneFailure)
            throw GitWorktreeException(
                "Failed to delete leftover worktree directory at $worktreePath",
                deleteFailure,
            )
        }

        if (pruneFailure != null) throw pruneFailure
    }

    companion object {
        fun buildWorktreePath(repoPath: String, branch: String): String =
            com.github.karlsabo.git.buildWorktreePath(repoPath, branch).value

        @Suppress("unused")
        fun sanitizeBranchName(branch: String): String =
            com.github.karlsabo.git.sanitizeBranchName(branch)

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

    private fun validateWorktreeBranchName(branch: String) {
        val validation = branchNameValidator.validate(branch)
        if (!validation.isValid) {
            throw GitWorktreeException("Invalid worktree branch name: ${validation.message}")
        }
    }

    private fun listWorktreeEntries(repoPath: String): List<Worktree> {
        val output = try {
            gitCommandApi.worktreeList(repoPath)
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to list worktrees for $repoPath: ${e.gitOutput}", e)
        }

        return parseWorktreeListPorcelain(output)
    }

    private fun isWorktreeDirty(worktreePath: String): Boolean {
        val output = try {
            gitCommandApi.status(worktreePath)
        } catch (e: GitCommandException) {
            logger.warn(e) { "Failed to read worktree status for $worktreePath; treating it as dirty" }
            return true
        }
        return output.isNotBlank()
    }

    private fun removeWorktree(repoPath: String, worktreePath: String, force: Boolean) {
        removeWorktree(worktreePath) {
            if (force) {
                gitCommandApi.execute(repoPath, "worktree", "remove", "--force", worktreePath)
            } else {
                gitCommandApi.worktreeRemove(repoPath, worktreePath)
            }
        }
    }

    private fun removeWorktree(worktreePath: String, removeCommand: () -> Unit) {
        try {
            removeCommand()
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to remove worktree at $worktreePath: ${e.gitOutput}", e)
        }
        logger.info { "Removed worktree at $worktreePath" }
    }

    private fun pruneWorktrees(repoPath: String) {
        try {
            gitCommandApi.execute(repoPath, "worktree", "prune")
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to prune worktrees for $repoPath: ${e.gitOutput}", e)
        }
        logger.info { "Pruned worktree metadata for $repoPath" }
    }
}

private fun deleteCheckoutDirectory(worktreePath: String) {
    val path = Path(worktreePath)
    if (!SystemFileSystem.exists(path)) return
    deleteRecursively(path)
    logger.info { "Deleted leftover worktree directory at $worktreePath" }
}

private fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}
