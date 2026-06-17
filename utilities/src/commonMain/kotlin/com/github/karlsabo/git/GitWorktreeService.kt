package com.github.karlsabo.git

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

private fun logGitWorktreeWarning(message: String, cause: Throwable) {
    logger.warn(cause) { message }
}

open class GitWorktreeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ExistingTargetBranchAncestryException(
    val baseBranch: String,
    val targetBranch: String,
) : GitWorktreeException(existingTargetBranchAncestryFailureMessage(baseBranch, targetBranch))

class GitRebaseConflictException(
    val worktreePath: String,
    val parentBranch: String,
    cause: Throwable,
) : GitWorktreeException(rebaseConflictMessage(worktreePath, parentBranch), cause)

class GitWorktreeService private constructor(
    parts: GitWorktreeServiceParts,
) : GitWorktreeApi,
    GitRepositoryApi by parts.repositoryApi,
    GitWorktreeCreationApi by parts.creationApi,
    GitWorktreeDiscoveryApi by parts.discoveryApi,
    GitWorktreeArchiveApi by parts.archiveApi,
    GitWorktreeRebaseApi by parts.rebaseApi {
    constructor(
        gitCommandApi: GitCommandApi = GitCommandService(),
        deleteCheckoutDirectory: (String) -> Unit = ::deleteCheckoutDirectory,
        branchNameValidator: WorktreeBranchNameValidator = WorktreeBranchNameValidator { branch ->
            gitCommandApi.isValidBranchRefFormat(branch)
        },
        logWarning: (message: String, cause: Throwable) -> Unit = ::logGitWorktreeWarning,
    ) : this(
        buildGitWorktreeServiceParts(
            gitCommandApi = gitCommandApi,
            deleteCheckoutDirectory = deleteCheckoutDirectory,
            branchNameValidator = branchNameValidator,
            logWarning = logWarning,
        ),
    )
}

private data class GitWorktreeServiceParts(
    val repositoryApi: GitRepositoryApi,
    val creationApi: GitWorktreeCreationApi,
    val discoveryApi: GitWorktreeDiscoveryApi,
    val archiveApi: GitWorktreeArchiveApi,
    val rebaseApi: GitWorktreeRebaseApi,
)

private fun buildGitWorktreeServiceParts(
    gitCommandApi: GitCommandApi,
    deleteCheckoutDirectory: (String) -> Unit,
    branchNameValidator: WorktreeBranchNameValidator,
    logWarning: (message: String, cause: Throwable) -> Unit,
): GitWorktreeServiceParts {
    val branchValidator = GitWorktreeBranchValidator(branchNameValidator)
    val lister = GitWorktreeLister(gitCommandApi)
    val repoResolver = GitRepositoryResolver(gitCommandApi, lister)
    val ancestryChecker = GitBranchAncestryChecker(gitCommandApi, branchValidator)
    val planner = GitWorktreeBranchPlanner(gitCommandApi, lister, branchValidator)
    val creator = GitWorktreeCreator(gitCommandApi, branchValidator, planner, ancestryChecker)
    val defaultBranchRefResolver = GitDefaultBranchRefResolver(gitCommandApi)
    val parentInferer = GitWorktreeParentInferer(gitCommandApi, lister, defaultBranchRefResolver, logWarning)
    val archiver = GitWorktreeArchiver(gitCommandApi, deleteCheckoutDirectory)
    val rebaser = GitWorktreeRebaser(gitCommandApi, branchValidator)

    return GitWorktreeServiceParts(
        repositoryApi = GitRepositoryService(repoResolver),
        creationApi = GitWorktreeCreationService(creator, planner, ancestryChecker),
        discoveryApi = GitWorktreeDiscoveryService(
            lister,
            defaultBranchRefResolver,
            parentInferer,
            ancestryChecker,
        ),
        archiveApi = GitWorktreeArchiveService(archiver),
        rebaseApi = GitWorktreeRebaseService(rebaser),
    )
}

private class GitRepositoryService(
    private val resolver: GitRepositoryResolver,
) : GitRepositoryApi {
    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        resolver.ensureRepository(repoPath, cloneUrl)
    }

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        val repositoryRoot = resolver.resolveRepositoryRoot(selectedPath)
        return repositoryRoot
    }
}

private class GitWorktreeCreationService(
    private val creator: GitWorktreeCreator,
    private val planner: GitWorktreeBranchPlanner,
    private val ancestryChecker: GitBranchAncestryChecker,
) : GitWorktreeCreationApi {
    override fun ensureWorktree(repoPath: String, branch: String): String = creator.ensureWorktree(repoPath, branch)

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean,
    ): String = creator.createBranchWorktree(
        repoPath = repoPath,
        baseWorktreePath = baseWorktreePath,
        baseBranch = baseBranch,
        targetBranch = targetBranch,
        allowUnrelatedExistingBranch = allowUnrelatedExistingBranch,
    )

    override fun createBranchWorktreeFromCommitIsh(
        repoPath: String,
        baseWorktreePath: String,
        baseCommitIsh: String,
        targetBranch: String,
    ): String = creator.createBranchWorktreeFromCommitIsh(
        repoPath = repoPath,
        baseWorktreePath = baseWorktreePath,
        baseCommitIsh = baseCommitIsh,
        targetBranch = targetBranch,
    )

    override fun planBranchWorktreeCreation(
        repoPath: String,
        targetBranch: String,
    ): BranchWorktreeCreationPlan = creator.planBranchWorktreeCreation(repoPath, targetBranch)

    override fun worktreeExists(repoPath: String, branch: String): Boolean = planner.worktreeExists(repoPath, branch)

    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean = ancestryChecker.isBranchAncestor(repoPath, baseBranch, childBranch)
}

private class GitWorktreeDiscoveryService(
    private val lister: GitWorktreeLister,
    private val defaultRefs: GitDefaultBranchRefResolver,
    private val parents: GitWorktreeParentInferer,
    private val ancestryChecker: GitBranchAncestryChecker,
) : GitWorktreeDiscoveryApi {
    override fun listWorktrees(repoPath: String): List<Worktree> = lister.listWorktrees(repoPath)

    override fun inferDefaultBranchRef(repoPath: String): String? = defaultRefs.inferDefaultBranchRef(repoPath)

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> {
        val parentBranches = parents.inferParentBranches(repoPath)
        return parentBranches
    }

    override fun branchNeedsRebase(
        repoPath: String,
        parentBranch: String,
        childBranch: String,
    ): Boolean = ancestryChecker.branchNeedsRebase(repoPath, parentBranch, childBranch)
}

private class GitWorktreeRebaseService(
    private val rebaser: GitWorktreeRebaser,
) : GitWorktreeRebaseApi {
    override fun rebaseWorktreeOntoParent(
        worktreePath: String,
        parentBranch: String,
    ) {
        rebaser.rebaseWorktreeOntoParent(worktreePath, parentBranch)
    }
}

private class GitWorktreeArchiveService(
    private val archiver: GitWorktreeArchiver,
) : GitWorktreeArchiveApi {
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
    private val branchValidator: GitWorktreeBranchValidator,
    private val planner: GitWorktreeBranchPlanner,
    private val ancestryChecker: GitBranchAncestryChecker,
) {
    fun ensureWorktree(repoPath: String, branch: String): String {
        branchValidator.validate(branch)
        val worktreePath = buildWorktreePath(repoPath, branch).value
        if (planner.worktreeExists(repoPath, branch)) {
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
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        require(baseWorktreePath.isNotBlank()) { "baseWorktreePath must not be blank" }
        branchValidator.validate(baseBranch)
        return when (val plan = planBranchWorktreeCreation(repoPath, targetBranch)) {
            is BranchWorktreeCreationPlan.ReuseExistingWorktree -> {
                logger.info { "Worktree already exists at ${plan.worktreePath} for branch $targetBranch" }
                plan.worktreePath
            }

            is BranchWorktreeCreationPlan.UseExistingLocalBranch -> createExistingBranchWorktree(
                repoPath = repoPath,
                baseBranch = baseBranch,
                targetBranch = targetBranch,
                allowUnrelatedExistingBranch = allowUnrelatedExistingBranch,
                plan = plan,
            )

            is BranchWorktreeCreationPlan.CreateNewBranch -> createNewBranchWorktree(
                baseWorktreePath = baseWorktreePath,
                baseCommitIsh = baseBranch,
                targetBranch = targetBranch,
                plan = plan,
            )
        }
    }

    fun createBranchWorktreeFromCommitIsh(
        repoPath: String,
        baseWorktreePath: String,
        baseCommitIsh: String,
        targetBranch: String,
    ): String {
        require(baseWorktreePath.isNotBlank()) { "baseWorktreePath must not be blank" }
        require(baseCommitIsh.isNotBlank()) { "baseCommitIsh must not be blank" }
        return when (val plan = planBranchWorktreeCreation(repoPath, targetBranch)) {
            is BranchWorktreeCreationPlan.ReuseExistingWorktree -> {
                logger.info { "Worktree already exists at ${plan.worktreePath} for branch $targetBranch" }
                plan.worktreePath
            }

            is BranchWorktreeCreationPlan.UseExistingLocalBranch -> throw GitWorktreeException(
                "Local branch $targetBranch already exists. Choose a different branch name.",
            )

            is BranchWorktreeCreationPlan.CreateNewBranch -> createNewBranchWorktree(
                baseWorktreePath = baseWorktreePath,
                baseCommitIsh = baseCommitIsh,
                targetBranch = targetBranch,
                plan = plan,
            )
        }
    }

    fun planBranchWorktreeCreation(
        repoPath: String,
        targetBranch: String,
    ): BranchWorktreeCreationPlan = planner.planBranchWorktreeCreation(repoPath, targetBranch)

    private fun createExistingBranchWorktree(
        repoPath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean,
        plan: BranchWorktreeCreationPlan.UseExistingLocalBranch,
    ): String {
        if (!allowUnrelatedExistingBranch && !ancestryChecker.isBranchAncestor(repoPath, baseBranch, targetBranch)) {
            throw ExistingTargetBranchAncestryException(baseBranch = baseBranch, targetBranch = targetBranch)
        }
        addExistingBranchWorktree(repoPath, targetBranch, plan.worktreePath)
        logger.info { "Created worktree at ${plan.worktreePath} for existing branch $targetBranch" }
        return plan.worktreePath
    }

    private fun createNewBranchWorktree(
        baseWorktreePath: String,
        baseCommitIsh: String,
        targetBranch: String,
        plan: BranchWorktreeCreationPlan.CreateNewBranch,
    ): String {
        addNewBranchWorktree(baseWorktreePath, targetBranch, plan.worktreePath, baseCommitIsh)
        logger.info { "Created worktree at ${plan.worktreePath} for branch $targetBranch from $baseCommitIsh" }
        return plan.worktreePath
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

    private fun addNewBranchWorktree(
        baseWorktreePath: String,
        targetBranch: String,
        worktreePath: String,
        baseCommitIsh: String,
    ) {
        try {
            gitCommandApi.worktreeAddNewBranch(baseWorktreePath, targetBranch, worktreePath, baseCommitIsh)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for branch $targetBranch " +
                    "from $baseCommitIsh: ${e.gitOutput}",
                e,
            )
        }
    }

    private fun addExistingBranchWorktree(
        repoPath: String,
        targetBranch: String,
        worktreePath: String,
    ) {
        try {
            gitCommandApi.worktreeAdd(repoPath, worktreePath, targetBranch)
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to create worktree at $worktreePath for existing branch $targetBranch: ${e.gitOutput}",
                e,
            )
        }
    }
}

private class GitWorktreeBranchPlanner(
    private val gitCommandApi: GitCommandApi,
    private val lister: GitWorktreeLister,
    private val branchValidator: GitWorktreeBranchValidator,
) {
    fun planBranchWorktreeCreation(repoPath: String, targetBranch: String): BranchWorktreeCreationPlan {
        branchValidator.validate(targetBranch)
        val worktreePath = buildWorktreePath(repoPath, targetBranch).value
        val existingWorktrees = lister.listWorktreeEntries(repoPath)

        failIfBranchCheckedOutElsewhere(existingWorktrees, targetBranch, worktreePath)
        return when {
            exactTargetWorktreeExists(existingWorktrees, targetBranch, worktreePath) -> {
                BranchWorktreeCreationPlan.ReuseExistingWorktree(
                    targetBranch = targetBranch,
                    worktreePath = worktreePath,
                )
            }

            localBranchExists(repoPath, targetBranch) -> {
                BranchWorktreeCreationPlan.UseExistingLocalBranch(
                    targetBranch = targetBranch,
                    worktreePath = worktreePath,
                )
            }

            else -> {
                failIfRemoteBranchExists(repoPath, targetBranch)
                BranchWorktreeCreationPlan.CreateNewBranch(
                    targetBranch = targetBranch,
                    worktreePath = worktreePath,
                )
            }
        }
    }

    fun worktreeExists(repoPath: String, branch: String): Boolean {
        val worktreePath = buildWorktreePath(repoPath, branch).value
        return lister.listWorktreeEntries(repoPath).any { it.path == worktreePath }
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

    private fun localBranchExists(repoPath: String, targetBranch: String): Boolean = try {
        gitCommandApi.localBranchExists(repoPath, targetBranch)
    } catch (e: GitCommandException) {
        throw GitWorktreeException("Failed to check local branch $targetBranch: ${e.gitOutput}", e)
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
}

private fun existingTargetBranchAncestryFailureMessage(
    baseBranch: String,
    targetBranch: String,
): String = "Existing branch $targetBranch is not descended from selected base $baseBranch. " +
    "Choose a different branch or start from the correct base."

private fun rebaseConflictMessage(
    worktreePath: String,
    parentBranch: String,
): String = "Rebase conflict while rebasing worktree $worktreePath onto $parentBranch"

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
            gitCommandApi.isAncestor(repoPath, baseBranch.toAncestryRef(), childBranch.toAncestryRef())
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to check whether $baseBranch is an ancestor of $childBranch: ${e.gitOutput}",
                e,
            )
        }
    }

    fun branchNeedsRebase(
        repoPath: String,
        parentBranch: String,
        childBranch: String,
    ): Boolean {
        branchValidator.validate(parentBranch)
        branchValidator.validate(childBranch)
        return try {
            gitCommandApi.hasCommitsNotContainedIn(
                repoPath,
                parentBranch.toAncestryRef(),
                childBranch.toAncestryRef(),
            )
        } catch (e: GitCommandException) {
            throw GitWorktreeException(
                "Failed to check whether $parentBranch has commits not contained in $childBranch: ${e.gitOutput}",
                e,
            )
        }
    }

    private fun String.toAncestryRef(): String = when {
        startsWith("refs/") -> this
        endsWith("/HEAD") -> this
        else -> "refs/heads/$this"
    }
}

private class GitDefaultBranchRefResolver(
    private val gitCommandApi: GitCommandApi,
) {
    fun inferDefaultBranchRef(repoPath: String): String? = inferDefaultBranchRef(
        repoPath = repoPath,
        beforeRemoteDefaultRef = {},
    )

    fun inferDefaultBranchRef(
        repoPath: String,
        beforeRemoteDefaultRef: (remote: String) -> Unit,
    ): String? {
        for (remote in candidateDefaultBranchRemotes(repoPath)) {
            beforeRemoteDefaultRef(remote)
            remoteDefaultBranchRef(repoPath, remote)?.let { return it }
        }
        return null
    }

    private fun candidateDefaultBranchRemotes(repoPath: String): List<String> = listOfNotNull(
        currentBranchUpstreamRemote(repoPath)?.takeIf { it.isNotBlank() },
        DEFAULT_REMOTE,
    ).distinct()

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
    private val logWarning: (message: String, cause: Throwable) -> Unit,
) {
    fun inferParentBranches(repoPath: String): Map<String, String> {
        val visibleBranches = lister.listWorktreeEntries(repoPath)
            .map { it.branch }
            .filter { it.isNotBlank() }
            .distinct()
        val defaultBranchRef = defaultBranchRefResolver.inferDefaultBranchRef(repoPath) { remote ->
            fetchRemoteBestEffort(repoPath, remote)
        }

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
        return parentCandidates(childBranch, visibleBranches, defaultBranchRef)
            .ancestorCandidatesOrNull(repoPath, childRef)
            ?.takeIf { it.isNotEmpty() }
            ?.let { ancestors -> dropDefaultRefDuplicates(repoPath, ancestors) }
            ?.let { uniqueAncestors -> nearestAncestorsOrNull(repoPath, uniqueAncestors) }
            ?.singleOrNull()
            ?.branch
    }

    private fun parentCandidates(
        childBranch: String,
        visibleBranches: List<String>,
        defaultBranchRef: String?,
    ): List<GitWorktreeParentCandidate> = visibleBranches
        .filter { it != childBranch }
        .map { GitWorktreeParentCandidate(branch = it, ref = "refs/heads/$it") }
        .plus(defaultBranchRef?.let { GitWorktreeParentCandidate(branch = null, ref = it) })
        .filterNotNull()

    private fun List<GitWorktreeParentCandidate>.ancestorCandidatesOrNull(
        repoPath: String,
        childRef: String,
    ): List<GitWorktreeParentCandidate>? {
        val ancestors = mutableListOf<GitWorktreeParentCandidate>()
        var hasFailure = false
        forEach { candidate ->
            when (isAncestor(repoPath, candidate.ref, childRef)) {
                null -> hasFailure = true
                true -> ancestors += candidate
                false -> Unit
            }
        }
        return if (hasFailure) null else ancestors
    }

    private fun nearestAncestorsOrNull(
        repoPath: String,
        uniqueAncestors: List<GitWorktreeParentCandidate>,
    ): List<GitWorktreeParentCandidate>? {
        val nearestAncestors = mutableListOf<GitWorktreeParentCandidate>()
        var hasFailure = false
        uniqueAncestors.forEach { candidate ->
            when (candidate.hasNearerAncestorOrNull(repoPath, uniqueAncestors)) {
                null -> hasFailure = true
                true -> Unit
                false -> nearestAncestors += candidate
            }
        }
        return if (hasFailure) null else nearestAncestors
    }

    private fun GitWorktreeParentCandidate.hasNearerAncestorOrNull(
        repoPath: String,
        uniqueAncestors: List<GitWorktreeParentCandidate>,
    ): Boolean? = uniqueAncestors
        .asSequence()
        .filter { it != this }
        .fold(false as Boolean?) { result, other ->
            when (result) {
                null, true -> result
                false -> isAncestor(repoPath, ref, other.ref)
            }
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
        var hasEquivalent = false
        var hasFailure = false
        ancestors
            .filter { other -> other.branch != null }
            .forEach { other ->
                val candidateMatchesOther = isAncestor(repoPath, candidate.ref, other.ref)
                val otherMatchesCandidate = isAncestor(repoPath, other.ref, candidate.ref)
                if (candidateMatchesOther == null || otherMatchesCandidate == null) {
                    hasFailure = true
                } else if (candidateMatchesOther && otherMatchesCandidate) {
                    hasEquivalent = true
                }
            }
        return if (hasFailure) null else hasEquivalent
    }

    private fun fetchRemoteBestEffort(repoPath: String, remote: String) {
        try {
            gitCommandApi.fetch(repoPath, remote)
        } catch (e: GitCommandException) {
            logWarning(
                "Failed to fetch $remote before inferring worktree hierarchy; using local refs",
                e,
            )
        }
    }

    private fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean? = try {
        gitCommandApi.isAncestor(repoPath, ancestorRef, descendantRef)
    } catch (e: GitCommandException) {
        logWarning(
            "Failed to infer worktree parent from $ancestorRef to $descendantRef; leaving affected worktree flat",
            e,
        )
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

private class GitWorktreeRebaser(
    private val gitCommandApi: GitCommandApi,
    private val branchValidator: GitWorktreeBranchValidator,
) {
    fun rebaseWorktreeOntoParent(
        worktreePath: String,
        parentBranch: String,
    ) {
        require(worktreePath.isNotBlank()) { "worktreePath must not be blank" }
        branchValidator.validate(parentBranch)
        try {
            gitCommandApi.rebase(worktreePath, parentBranch)
        } catch (e: GitCommandException) {
            if (rebaseInProgress(worktreePath)) {
                throw GitRebaseConflictException(
                    worktreePath = worktreePath,
                    parentBranch = parentBranch,
                    cause = e,
                )
            }
            throw GitWorktreeException(
                "Failed to rebase worktree $worktreePath onto $parentBranch: ${e.gitOutput}",
                e,
            )
        }
    }

    private fun rebaseInProgress(worktreePath: String): Boolean = listOf("rebase-merge", "rebase-apply")
        .mapNotNull { rebaseStatePath(worktreePath, it) }
        .any { SystemFileSystem.exists(it) }

    private fun rebaseStatePath(worktreePath: String, stateDirectory: String): Path? {
        val gitPath = try {
            gitCommandApi.revParse(worktreePath, "--git-path", stateDirectory)
        } catch (_: GitCommandException) {
            return null
        }.takeIf { it.isNotBlank() } ?: return null

        return if (gitPath.isAbsolutePath()) Path(gitPath) else Path(worktreePath, gitPath)
    }

    private fun String.isAbsolutePath(): Boolean = startsWith("/") || matches(Regex("^[A-Za-z]:[\\\\/].*"))
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
