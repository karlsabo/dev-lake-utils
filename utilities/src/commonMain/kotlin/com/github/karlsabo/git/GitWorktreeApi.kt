package com.github.karlsabo.git

interface GitWorktreeApi {
    fun ensureRepository(repoPath: String, cloneUrl: String)
    fun ensureWorktree(repoPath: String, branch: String): String
    fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String
    fun worktreeExists(repoPath: String, branch: String): Boolean
    fun isBranchAncestor(repoPath: String, baseBranch: String, childBranch: String): Boolean
    fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees
    fun listWorktrees(repoPath: String): List<Worktree>
    fun removeWorktree(worktreePath: String, force: Boolean = false)
    fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean = false)
}

data class RepositoryWorktrees(
    val rootPath: String,
    val selectedWorktreePath: String,
    val worktrees: List<Worktree>,
)
