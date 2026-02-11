package com.github.karlsabo.git

interface GitWorktreeApi {
    fun ensureWorktree(repoPath: String, branch: String): String
    fun worktreeExists(repoPath: String, branch: String): Boolean
    fun listWorktrees(repoPath: String): List<Worktree>
    fun removeWorktree(worktreePath: String)
}
