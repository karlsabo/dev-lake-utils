package com.github.karlsabo.git

interface GitWorktreeApi :
    GitRepositoryApi,
    GitWorktreeCreationApi,
    GitWorktreeDiscoveryApi,
    GitWorktreeArchiveApi

interface GitRepositoryApi {
    fun ensureRepository(repoPath: String, cloneUrl: String)
    fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees
}

interface GitWorktreeCreationApi {
    fun ensureWorktree(repoPath: String, branch: String): String
    fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean = false,
    ): String
    fun planBranchWorktreeCreation(
        repoPath: String,
        targetBranch: String,
    ): BranchWorktreeCreationPlan = throw UnsupportedOperationException(
        "planBranchWorktreeCreation is not implemented",
    )
    fun worktreeExists(repoPath: String, branch: String): Boolean
    fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean
}

interface GitWorktreeDiscoveryApi {
    fun listWorktrees(repoPath: String): List<Worktree>
    fun inferDefaultBranchRef(repoPath: String): String?
    fun inferWorktreeParentBranches(repoPath: String): Map<String, String>
}

interface GitWorktreeArchiveApi {
    fun removeWorktree(worktreePath: String, force: Boolean = false)
    fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean = false,
    )
}

data class RepositoryWorktrees(
    val rootPath: String,
    val selectedWorktreePath: String,
    val worktrees: List<Worktree>,
)

sealed interface BranchWorktreeCreationPlan {
    val targetBranch: String
    val worktreePath: String

    data class CreateNewBranch(
        override val targetBranch: String,
        override val worktreePath: String,
    ) : BranchWorktreeCreationPlan

    data class UseExistingLocalBranch(
        override val targetBranch: String,
        override val worktreePath: String,
    ) : BranchWorktreeCreationPlan

    data class ReuseExistingWorktree(
        override val targetBranch: String,
        override val worktreePath: String,
    ) : BranchWorktreeCreationPlan
}
