package com.github.karlsabo.git

interface GitRepositoryCommandApi {
    fun clone(url: String, targetPath: String)
    fun isGitRepository(repoPath: String): Boolean
    fun checkout(repoPath: String, ref: String)
}

interface GitRemoteCommandApi {
    fun fetch(
        repoPath: String,
        remote: String = "origin",
        vararg refSpecs: String,
    )

    fun remoteBranchExists(
        repoPath: String,
        branch: String,
        remote: String = "origin",
    ): Boolean

    fun currentBranchUpstreamRemote(repoPath: String): String?

    fun remoteDefaultBranchRef(
        repoPath: String,
        remote: String = "origin",
    ): String?
}

interface GitBranchCommandApi {
    fun localBranchExists(
        repoPath: String,
        branch: String,
    ): Boolean
}

interface GitAncestryCommandApi {
    fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean

    fun hasCommitsNotContainedIn(
        repoPath: String,
        sourceRef: String,
        containingRef: String,
    ): Boolean
}

interface GitWorktreeCommandApi {
    fun worktreeAdd(
        repoPath: String,
        path: String,
        commitIsh: String,
    )

    fun worktreeAddNewBranch(
        repoPath: String,
        newBranch: String,
        path: String,
        baseCommitIsh: String,
    )

    fun worktreeList(repoPath: String): String
    fun worktreeRemove(repoPath: String, path: String)
}

interface GitWorkingTreeCommandApi {
    fun status(repoPath: String): String
}

interface GitHistoryCommandApi {
    fun log(repoPath: String, vararg args: String): String
    fun diff(repoPath: String, vararg args: String): String
    fun revParse(repoPath: String, vararg args: String): String
}

interface GitRawCommandExecutor {
    fun execute(repoPath: String? = null, vararg args: String): String
}

interface GitCommandApi :
    GitRepositoryCommandApi,
    GitRemoteCommandApi,
    GitBranchCommandApi,
    GitAncestryCommandApi,
    GitWorktreeCommandApi,
    GitWorkingTreeCommandApi,
    GitHistoryCommandApi,
    GitRawCommandExecutor
