package com.github.karlsabo.git

internal class FakeGitCommandApi : GitCommandApi {

    data class Call(
        val method: String,
        val args: List<String> = emptyList(),
    )

    data class WorktreeAddNewBranchCall(
        val repoPath: String,
        val newBranch: String,
        val path: String,
        val baseCommitIsh: String,
    )

    val calls = mutableListOf<Call>()

    var cloneAction: (String, String) -> Unit = { _, _ -> }
    var isGitRepositoryResult: Boolean = true
    var fetchAction: (String, String, Array<out String>) -> Unit = { _, _, _ -> }
    var remoteBranchExistsAction: (String, String, String) -> Boolean = { _, _, _ -> false }
    var localBranchExistsAction: (String, String) -> Boolean = { _, _ -> false }
    var currentBranchUpstreamRemoteAction: (String) -> String? = { null }
    var remoteDefaultBranchRefAction: (String, String) -> String? = { _, _ -> null }
    var isAncestorAction: (String, String, String) -> Boolean = { _, _, _ -> false }
    var hasCommitsNotContainedInAction: (String, String, String) -> Boolean = { _, _, _ -> false }
    var worktreeAddAction: (String, String, String) -> Unit = { _, _, _ -> }
    var worktreeAddNewBranchAction: (WorktreeAddNewBranchCall) -> Unit = {}
    var rebaseAction: (String, String) -> Unit = { _, _ -> }
    var worktreeListResult: String = ""
    var worktreeListAction: (String) -> String = { worktreeListResult }
    var worktreeRemoveAction: (String, String) -> Unit = { _, _ -> }
    var statusAction: (String) -> String = { "" }
    var revParseAction: (String, Array<out String>) -> String = { _, _ -> "" }
    var executeAction: (String?, Array<out String>) -> String = { _, _ -> "" }

    override fun clone(url: String, targetPath: String) {
        calls.add(Call("clone", listOf(url, targetPath)))
        cloneAction(url, targetPath)
    }

    override fun isGitRepository(repoPath: String): Boolean {
        calls.add(Call("isGitRepository", listOf(repoPath)))
        return isGitRepositoryResult
    }

    override fun fetch(
        repoPath: String,
        remote: String,
        vararg refSpecs: String,
    ) {
        calls.add(Call("fetch", listOf(repoPath, remote) + refSpecs.toList()))
        fetchAction(repoPath, remote, refSpecs)
    }

    override fun remoteBranchExists(
        repoPath: String,
        branch: String,
        remote: String,
    ): Boolean {
        calls.add(Call("remoteBranchExists", listOf(repoPath, branch, remote)))
        return remoteBranchExistsAction(repoPath, branch, remote)
    }

    override fun localBranchExists(
        repoPath: String,
        branch: String,
    ): Boolean {
        calls.add(Call("localBranchExists", listOf(repoPath, branch)))
        return localBranchExistsAction(repoPath, branch)
    }

    override fun currentBranchUpstreamRemote(repoPath: String): String? {
        calls.add(Call("currentBranchUpstreamRemote", listOf(repoPath)))
        return currentBranchUpstreamRemoteAction(repoPath)
    }

    override fun remoteDefaultBranchRef(repoPath: String, remote: String): String? {
        calls.add(Call("remoteDefaultBranchRef", listOf(repoPath, remote)))
        return remoteDefaultBranchRefAction(repoPath, remote)
    }

    override fun isAncestor(
        repoPath: String,
        ancestorRef: String,
        descendantRef: String,
    ): Boolean {
        calls.add(Call("isAncestor", listOf(repoPath, ancestorRef, descendantRef)))
        return isAncestorAction(repoPath, ancestorRef, descendantRef)
    }

    override fun hasCommitsNotContainedIn(
        repoPath: String,
        sourceRef: String,
        containingRef: String,
    ): Boolean {
        calls.add(Call("hasCommitsNotContainedIn", listOf(repoPath, sourceRef, containingRef)))
        return hasCommitsNotContainedInAction(repoPath, sourceRef, containingRef)
    }

    override fun worktreeAdd(
        repoPath: String,
        path: String,
        commitIsh: String,
    ) {
        calls.add(Call("worktreeAdd", listOf(repoPath, path, commitIsh)))
        worktreeAddAction(repoPath, path, commitIsh)
    }

    override fun worktreeAddNewBranch(
        repoPath: String,
        newBranch: String,
        path: String,
        baseCommitIsh: String,
    ) {
        calls.add(Call("worktreeAddNewBranch", listOf(repoPath, newBranch, path, baseCommitIsh)))
        worktreeAddNewBranchAction(
            WorktreeAddNewBranchCall(
                repoPath = repoPath,
                newBranch = newBranch,
                path = path,
                baseCommitIsh = baseCommitIsh,
            ),
        )
    }

    override fun worktreeList(repoPath: String): String {
        calls.add(Call("worktreeList", listOf(repoPath)))
        return worktreeListAction(repoPath)
    }

    override fun worktreeRemove(repoPath: String, path: String) {
        calls.add(Call("worktreeRemove", listOf(repoPath, path)))
        worktreeRemoveAction(repoPath, path)
    }

    override fun rebase(repoPath: String, upstreamRef: String) {
        calls.add(Call("rebase", listOf(repoPath, upstreamRef)))
        rebaseAction(repoPath, upstreamRef)
    }

    override fun checkout(repoPath: String, ref: String) {
        calls.add(Call("checkout", listOf(repoPath, ref)))
    }

    override fun status(repoPath: String): String {
        calls.add(Call("status", listOf(repoPath)))
        return statusAction(repoPath)
    }

    override fun log(repoPath: String, vararg args: String): String {
        calls.add(Call("log", listOf(repoPath) + args.toList()))
        return ""
    }

    override fun diff(repoPath: String, vararg args: String): String {
        calls.add(Call("diff", listOf(repoPath) + args.toList()))
        return ""
    }

    override fun revParse(repoPath: String, vararg args: String): String {
        calls.add(Call("revParse", listOf(repoPath) + args.toList()))
        return revParseAction(repoPath, args)
    }

    override fun execute(repoPath: String?, vararg args: String): String {
        calls.add(Call("execute", listOfNotNull(repoPath) + args.toList()))
        return executeAction(repoPath, args)
    }
}
