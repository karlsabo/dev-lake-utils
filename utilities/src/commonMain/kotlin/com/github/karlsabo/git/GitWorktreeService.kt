package com.github.karlsabo.git

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

class GitWorktreeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class GitWorktreeService(
    gitCommandApi: GitCommandApi = GitCommandService(),
    deleteCheckoutDirectory: (String) -> Unit = ::deleteCheckoutDirectory,
    branchNameValidator: WorktreeBranchNameValidator = WorktreeBranchNameValidator { branch ->
        gitCommandApi.isValidBranchRefFormat(branch)
    },
) : GitWorktreeApi {
    private val branchValidator = GitWorktreeBranchValidator(branchNameValidator)
    private val lister = GitWorktreeLister(gitCommandApi)
    private val repoResolver = GitRepositoryResolver(gitCommandApi, lister)
    private val creator = GitWorktreeCreator(gitCommandApi, lister, branchValidator)
    private val ancestryChecker = GitBranchAncestryChecker(gitCommandApi, branchValidator)
    private val defaultBranchRefResolver = GitDefaultBranchRefResolver(gitCommandApi)
    private val parentInferer = GitWorktreeParentInferer(gitCommandApi, lister, defaultBranchRefResolver)
    private val archiver = GitWorktreeArchiver(gitCommandApi, deleteCheckoutDirectory)

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        repoResolver.ensureRepository(repoPath, cloneUrl)
    }

    override fun ensureWorktree(repoPath: String, branch: String): String = creator.ensureWorktree(repoPath, branch)

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String = creator.createBranchWorktree(repoPath, baseWorktreePath, baseBranch, targetBranch)

    override fun worktreeExists(repoPath: String, branch: String): Boolean = creator.worktreeExists(repoPath, branch)

    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean = ancestryChecker.isBranchAncestor(repoPath, baseBranch, childBranch)

    override fun resolveRepositoryRoot(
        selectedPath: String,
    ): RepositoryWorktrees = repoResolver.resolveRepositoryRoot(selectedPath)

    override fun listWorktrees(repoPath: String): List<Worktree> = lister.listWorktrees(repoPath)

    override fun inferDefaultBranchRef(repoPath: String): String? = defaultBranchRefResolver.inferDefaultBranchRef(repoPath)

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> = parentInferer.inferParentBranches(repoPath)

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        archiver.removeWorktree(worktreePath, force)
    }

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
        archiver.archiveWorktree(repoPath, worktreePath, force)
    }
}

private class GitWorktreeBranchValidator(
    private val branchNameValidator: WorktreeBranchNameValidator,
) {
    fun validate(branch: String) {
        val validation = branchNameValidator.validate(branch)
        if (!validation.isValid) {
            throw GitWorktreeException("Invalid worktree branch name: ${validation.message}")
        }
    }
}

private class GitRepositoryResolver(
    private val gitCommandApi: GitCommandApi,
    private val lister: GitWorktreeLister,
) {
    fun ensureRepository(repoPath: String, cloneUrl: String) {
        if (!SystemFileSystem.exists(Path(repoPath))) {
            cloneRepository(repoPath, cloneUrl)
        } else {
            verifyExistingRepository(repoPath)
        }
    }

    fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        verifySelectedPathIsRepository(selectedPath)
        val selectedWorktreePath = resolveSelectedWorktreePath(selectedPath)
        val worktrees = lister.listWorktrees(selectedWorktreePath)
        verifySelectedWorktreeIsListed(selectedPath, selectedWorktreePath, worktrees)

        return RepositoryWorktrees(
            rootPath = rootPathFrom(worktrees, selectedPath),
            selectedWorktreePath = selectedWorktreePath,
            worktrees = worktrees,
        )
    }

    private fun verifySelectedPathIsRepository(selectedPath: String) {
        if (!gitCommandApi.isGitRepository(selectedPath)) {
            throw GitWorktreeException("Directory $selectedPath is not a git repository")
        }
    }

    private fun rootPathFrom(worktrees: List<Worktree>, selectedPath: String): String = worktrees.firstOrNull()?.path
        ?: throw GitWorktreeException("No git worktrees found for $selectedPath")

    private fun verifySelectedWorktreeIsListed(
        selectedPath: String,
        selectedWorktreePath: String,
        worktrees: List<Worktree>,
    ) {
        if (worktrees.none { it.path == selectedWorktreePath }) {
            throw GitWorktreeException(
                "Could not match $selectedPath to a git worktree. Please add the repository root directory.",
            )
        }
    }

    private fun cloneRepository(repoPath: String, cloneUrl: String) {
        logger.info { "Repository not found at $repoPath, cloning from $cloneUrl" }
        try {
            gitCommandApi.clone(cloneUrl, repoPath)
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to clone $cloneUrl to $repoPath: ${e.gitOutput}", e)
        }
        logger.info { "Cloned $cloneUrl to $repoPath" }
    }

    private fun verifyExistingRepository(repoPath: String) {
        if (!gitCommandApi.isGitRepository(repoPath)) {
            throw GitWorktreeException("Directory $repoPath exists but is not a git repository")
        }
        logger.info { "Repository already exists at $repoPath" }
    }

    private fun resolveSelectedWorktreePath(selectedPath: String): String = try {
        gitCommandApi.revParse(selectedPath, "--show-toplevel")
    } catch (e: GitCommandException) {
        throw GitWorktreeException(
            "Could not resolve a worktree root for $selectedPath. Please add the repository root directory.",
            e,
        )
    }
}

private class GitWorktreeCreator(
    private val gitCommandApi: GitCommandApi,
    private val lister: GitWorktreeLister,
    private val branchValidator: GitWorktreeBranchValidator,
) {
    fun ensureWorktree(repoPath: String, branch: String): String {
        branchValidator.validate(branch)
        val worktreePath = buildWorktreePath(repoPath, branch).value
        if (lister.listWorktreeEntries(repoPath).any { it.path == worktreePath }) {
            logger.info { "Worktree already exists at $worktreePath" }
            return worktreePath
        }

        fetchBranchBestEffort(repoPath, branch)
        addWorktree(repoPath, worktreePath, branch)
        logger.info { "Created worktree at $worktreePath for branch $branch" }
        return worktreePath
    }

    fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String {
        require(baseWorktreePath.isNotBlank()) { "baseWorktreePath must not be blank" }
        branchValidator.validate(baseBranch)
        branchValidator.validate(targetBranch)
        val worktreePath = buildWorktreePath(repoPath, targetBranch).value
        val existingWorktrees = lister.listWorktreeEntries(repoPath)

        failIfBranchCheckedOutElsewhere(existingWorktrees, targetBranch, worktreePath)
        if (exactTargetWorktreeExists(existingWorktrees, targetBranch, worktreePath)) {
            logger.info { "Worktree already exists at $worktreePath for branch $targetBranch" }
            return worktreePath
        }
        failIfRemoteBranchExists(repoPath, targetBranch)
        addNewBranchWorktree(baseWorktreePath, targetBranch, worktreePath, baseBranch)
        logger.info { "Created worktree at $worktreePath for branch $targetBranch from $baseBranch" }
        return worktreePath
    }

    fun worktreeExists(repoPath: String, branch: String): Boolean {
        val worktreePath = buildWorktreePath(repoPath, branch).value
        return lister.listWorktreeEntries(repoPath).any { it.path == worktreePath }
    }

    private fun fetchBranchBestEffort(repoPath: String, branch: String) {
        try {
            gitCommandApi.fetch(repoPath, "origin", branch)
        } catch (e: GitCommandException) {
            logger.warn { "git fetch origin $branch failed (exitCode=${e.exitCode}): ${e.gitOutput}" }
        }
    }

    private fun addWorktree(
        repoPath: String,
        worktreePath: String,
        branch: String,
    ) {
        try {
            gitCommandApi.worktreeAdd(repoPath, worktreePath, branch)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for branch $branch: ${e.gitOutput}",
                e,
            )
        }
    }

    private fun failIfBranchCheckedOutElsewhere(
        worktrees: List<Worktree>,
        targetBranch: String,
        worktreePath: String,
    ) {
        val checkedOutElsewhere = worktrees.firstOrNull { worktree ->
            worktree.branch == targetBranch && worktree.path != worktreePath
        }
        if (checkedOutElsewhere != null) {
            throw GitWorktreeException(
                "Branch $targetBranch is already checked out elsewhere at ${checkedOutElsewhere.path}. " +
                    "Choose a different branch name.",
            )
        }
    }

    private fun exactTargetWorktreeExists(
        worktrees: List<Worktree>,
        targetBranch: String,
        worktreePath: String,
    ): Boolean = worktrees.any { worktree ->
        worktree.path == worktreePath && worktree.branch == targetBranch
    }

    private fun failIfRemoteBranchExists(repoPath: String, targetBranch: String) {
        val exists = try {
            gitCommandApi.remoteBranchExists(repoPath, targetBranch, remote = "origin")
        } catch (e: GitCommandException) {
            throw GitWorktreeException("Failed to check remote branch origin/$targetBranch: ${e.gitOutput}", e)
        }
        if (exists) {
            throw GitWorktreeException(
                "Remote branch origin/$targetBranch already exists. Choose a different branch name.",
            )
        }
    }

    private fun addNewBranchWorktree(
        baseWorktreePath: String,
        targetBranch: String,
        worktreePath: String,
        baseBranch: String,
    ) {
        try {
            gitCommandApi.worktreeAddNewBranch(baseWorktreePath, targetBranch, worktreePath, baseBranch)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for branch $targetBranch from $baseBranch: ${e.gitOutput}",
                e,
            )
        }
    }
}

private class GitBranchAncestryChecker(
    private val gitCommandApi: GitCommandApi,
    private val branchValidator: GitWorktreeBranchValidator,
) {
    fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean {
        branchValidator.validate(baseBranch)
        branchValidator.validate(childBranch)
        return try {
            gitCommandApi.isAncestor(repoPath, "refs/heads/$baseBranch", "refs/heads/$childBranch")
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to check whether $baseBranch is an ancestor of $childBranch: ${e.gitOutput}",
                e,
            )
        }
    }
}

private class GitDefaultBranchRefResolver(
    private val gitCommandApi: GitCommandApi,
) {
    fun inferDefaultBranchRef(repoPath: String): String? {
        val upstreamRemote = currentBranchUpstreamRemote(repoPath)
        if (!upstreamRemote.isNullOrBlank()) {
            val upstreamDefaultRef = remoteDefaultBranchRef(repoPath, upstreamRemote)
            if (upstreamDefaultRef != null) return upstreamDefaultRef
        }
        return remoteDefaultBranchRef(repoPath, DEFAULT_REMOTE)
    }

    private fun currentBranchUpstreamRemote(repoPath: String): String? = try {
        gitCommandApi.currentBranchUpstreamRemote(repoPath)
    } catch (_: GitCommandException) {
        null
    }

    private fun remoteDefaultBranchRef(repoPath: String, remote: String): String? = try {
        gitCommandApi.remoteDefaultBranchRef(repoPath, remote)
    } catch (_: GitCommandException) {
        null
    }

    private companion object {
        const val DEFAULT_REMOTE = "origin"
    }
}

private data class GitWorktreeParentCandidate(
    val branch: String?,
    val ref: String,
)

private class GitWorktreeParentInferer(
    private val gitCommandApi: GitCommandApi,
    private val lister: GitWorktreeLister,
    private val defaultBranchRefResolver: GitDefaultBranchRefResolver,
) {
    fun inferParentBranches(repoPath: String): Map<String, String> {
        val visibleBranches = lister.listWorktreeEntries(repoPath)
            .map { it.branch }
            .filter { it.isNotBlank() }
            .distinct()
        val defaultBranchRef = defaultBranchRefResolver.inferDefaultBranchRef(repoPath)

        return visibleBranches.mapNotNull { childBranch ->
            nearestParentBranch(repoPath, childBranch, visibleBranches, defaultBranchRef)?.let { parentBranch ->
                childBranch to parentBranch
            }
        }.toMap()
    }

    private fun nearestParentBranch(
        repoPath: String,
        childBranch: String,
        visibleBranches: List<String>,
        defaultBranchRef: String?,
    ): String? {
        val childRef = "refs/heads/$childBranch"
        val candidates = visibleBranches
            .filter { it != childBranch }
            .map { GitWorktreeParentCandidate(branch = it, ref = "refs/heads/$it") }
            .plus(defaultBranchRef?.let { GitWorktreeParentCandidate(branch = null, ref = it) })
            .filterNotNull()
        val ancestors = mutableListOf<GitWorktreeParentCandidate>()
        candidates.forEach { candidate ->
            val isAncestor = isAncestor(repoPath, candidate.ref, childRef) ?: return null
            if (isAncestor) ancestors += candidate
        }
        if (ancestors.isEmpty()) return null

        val uniqueAncestors = dropDefaultRefDuplicates(repoPath, ancestors) ?: return null

        val nearestAncestors = mutableListOf<GitWorktreeParentCandidate>()
        for (candidate in uniqueAncestors) {
            var hasNearerAncestor = false
            for (other in uniqueAncestors) {
                if (other == candidate) continue
                val otherIsDescendant = isAncestor(repoPath, candidate.ref, other.ref) ?: return null
                if (otherIsDescendant) {
                    hasNearerAncestor = true
                    break
                }
            }
            if (!hasNearerAncestor) nearestAncestors += candidate
        }

        return nearestAncestors.singleOrNull()?.branch
    }

    private fun dropDefaultRefDuplicates(
        repoPath: String,
        ancestors: List<GitWorktreeParentCandidate>,
    ): List<GitWorktreeParentCandidate>? {
        val uniqueAncestors = mutableListOf<GitWorktreeParentCandidate>()
        for (candidate in ancestors) {
            val isDuplicateDefaultRef = if (candidate.branch == null) {
                hasEquivalentVisibleBranch(repoPath, candidate, ancestors) ?: return null
            } else {
                false
            }
            if (!isDuplicateDefaultRef) uniqueAncestors += candidate
        }
        return uniqueAncestors
    }

    private fun hasEquivalentVisibleBranch(
        repoPath: String,
        candidate: GitWorktreeParentCandidate,
        ancestors: List<GitWorktreeParentCandidate>,
    ): Boolean? {
        for (other in ancestors) {
            if (other.branch == null) continue
            val candidateMatchesOther = isAncestor(repoPath, candidate.ref, other.ref) ?: return null
            val otherMatchesCandidate = isAncestor(repoPath, other.ref, candidate.ref) ?: return null
            if (candidateMatchesOther && otherMatchesCandidate) return true
        }
        return false
    }

    private fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean? = try {
        gitCommandApi.isAncestor(repoPath, ancestorRef, descendantRef)
    } catch (e: GitCommandException) {
        logger.warn(e) {
            "Failed to infer worktree parent from $ancestorRef to $descendantRef; leaving affected worktree flat"
        }
        null
    }
}

private class GitWorktreeLister(
    private val gitCommandApi: GitCommandApi,
) {
    fun listWorktrees(repoPath: String): List<Worktree> = listWorktreeEntries(repoPath).map { worktree ->
        worktree.copy(isDirty = isWorktreeDirty(worktree.path))
    }

    fun listWorktreeEntries(repoPath: String): List<Worktree> {
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
}

private class GitWorktreeArchiver(
    private val gitCommandApi: GitCommandApi,
    private val deleteCheckoutDirectory: (String) -> Unit,
) {
    fun removeWorktree(worktreePath: String, force: Boolean) {
        removeWorktree(worktreePath) {
            if (force) {
                gitCommandApi.execute(null, "worktree", "remove", "--force", worktreePath)
            } else {
                gitCommandApi.execute(null, "worktree", "remove", worktreePath)
            }
        }
    }

    fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
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

    private fun removeWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
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

private fun parseWorktreeListPorcelain(output: String): List<Worktree> {
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
