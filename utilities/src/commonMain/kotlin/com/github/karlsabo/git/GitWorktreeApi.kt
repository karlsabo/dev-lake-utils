package com.github.karlsabo.git

interface GitWorktreeApi {
    fun ensureRepository(repoPath: String, cloneUrl: String)
    fun ensureWorktree(repoPath: String, branch: String): String
    fun worktreeExists(repoPath: String, branch: String): Boolean
    fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees
    fun listWorktrees(repoPath: String): List<Worktree>
    fun removeWorktree(worktreePath: String)
    fun archiveWorktree(repoPath: String, worktreePath: String)
}

data class RepositoryWorktrees(
    val rootPath: String,
    val selectedWorktreePath: String,
    val worktrees: List<Worktree>,
)
