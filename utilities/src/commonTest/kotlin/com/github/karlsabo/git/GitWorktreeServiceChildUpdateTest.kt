package com.github.karlsabo.git

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitWorktreeServiceChildUpdateTest {
    @Test
    fun updateWorktreeFromParent_rebasesWhenChildOnlyHistoryIsEmpty() {
        val git = childUpdateGit(CommitGraph.emptyChildHistory())
        val service: GitWorktreeApi = GitWorktreeService(git)

        val strategy = service.updateWorktreeFromParent(WORKTREE_PATH, PARENT_BRANCH)

        assertEquals(WorktreeIntegrationStrategy.Rebase, strategy)
        assertSelectedOperation(git, expectedMethod = "rebase", expectedRef = PARENT_BRANCH)
    }

    @Test
    fun updateWorktreeFromParent_rebasesLinearChildOnlyHistory() {
        val git = childUpdateGit(CommitGraph.linearChildHistory())
        val service: GitWorktreeApi = GitWorktreeService(git)

        val strategy = service.updateWorktreeFromParent(WORKTREE_PATH, PARENT_BRANCH)

        assertEquals(WorktreeIntegrationStrategy.Rebase, strategy)
        assertSelectedOperation(git, expectedMethod = "rebase", expectedRef = PARENT_BRANCH)
    }

    @Test
    fun updateWorktreeFromParent_mergesResolvedRemoteParentWhenChildOnlyHistoryHasMerge() {
        val remoteRef = "origin/$PARENT_BRANCH"
        val git = childUpdateGit(
            graph = CommitGraph.childOnlyMergeHistory(baseRef = remoteRef),
            resolvedRemoteRef = remoteRef,
        )
        val service: GitWorktreeApi = GitWorktreeService(git)

        val strategy = service.updateWorktreeFromParent(WORKTREE_PATH, PARENT_BRANCH)

        assertEquals(WorktreeIntegrationStrategy.Merge, strategy)
        assertSelectedOperation(git, expectedMethod = "merge", expectedRef = remoteRef)
        assertEquals(1, git.calls.count { it.method == "fetch" })
    }

    @Test
    fun updateWorktreeFromParent_rebasesWhenMergeExistsOnlyInBaseHistory() {
        val git = childUpdateGit(CommitGraph.inheritedBaseMergeHistory())
        val service: GitWorktreeApi = GitWorktreeService(git)

        val strategy = service.updateWorktreeFromParent(WORKTREE_PATH, PARENT_BRANCH)

        assertEquals(WorktreeIntegrationStrategy.Rebase, strategy)
        assertSelectedOperation(git, expectedMethod = "rebase", expectedRef = PARENT_BRANCH)
    }

    @Test
    fun updateWorktreeFromParent_doesNotTryMergeWhenSelectedRebaseConflicts() {
        val worktreePath = createArchiveWorktreeTempDir()
        val rebaseStatePath = Path(worktreePath, ".git", "rebase-merge")
        val git = childUpdateGit(CommitGraph.emptyChildHistory(), worktreePath = worktreePath).apply {
            rebaseAction = { _, _ -> throw conflictFailure("rebase") }
            revParseAction = { _, args ->
                rebaseStatePath.toString().takeIf { args.toList() == listOf("--git-path", "rebase-merge") }.orEmpty()
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(git)

        try {
            SystemFileSystem.createDirectories(rebaseStatePath)

            assertFailsWith<GitRebaseConflictException> {
                service.updateWorktreeFromParent(worktreePath, PARENT_BRANCH)
            }

            assertSelectedOperation(git, "rebase", PARENT_BRANCH, worktreePath)
        } finally {
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun updateWorktreeFromParent_doesNotTryRebaseWhenSelectedMergeConflicts() {
        val worktreePath = createArchiveWorktreeTempDir()
        val mergeStatePath = Path(worktreePath, ".git", "MERGE_HEAD")
        val git = childUpdateGit(
            graph = CommitGraph.childOnlyMergeHistory(PARENT_BRANCH),
            worktreePath = worktreePath,
        ).apply {
            mergeAction = { _, _ -> throw conflictFailure("merge") }
            revParseAction = { _, args ->
                mergeStatePath.toString().takeIf { args.toList() == listOf("--git-path", "MERGE_HEAD") }.orEmpty()
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(git)

        try {
            SystemFileSystem.createDirectories(mergeStatePath)

            assertFailsWith<GitMergeConflictException> {
                service.updateWorktreeFromParent(worktreePath, PARENT_BRANCH)
            }

            assertSelectedOperation(git, "merge", PARENT_BRANCH, worktreePath)
        } finally {
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun updateWorktreeFromParent_doesNotIntegrateWhenHistoryInspectionFails() {
        val git = childUpdateGit(CommitGraph.emptyChildHistory()).apply {
            logAction = { _, _ ->
                throw GitCommandException(
                    command = listOf("git", "log"),
                    exitCode = 128,
                    gitOutput = "history unavailable",
                )
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(git)

        val failure = assertFailsWith<GitWorktreeException> {
            service.updateWorktreeFromParent(WORKTREE_PATH, PARENT_BRANCH)
        }

        assertEquals(
            "Failed to inspect child-only history in worktree $WORKTREE_PATH from $PARENT_BRANCH: " +
                "history unavailable",
            failure.message,
        )
        assertEquals(emptyList(), git.calls.filter { it.method == "rebase" || it.method == "merge" })
    }

    private fun childUpdateGit(
        graph: CommitGraph,
        resolvedRemoteRef: String? = null,
        worktreePath: String = WORKTREE_PATH,
    ): FakeGitCommandApi = FakeGitCommandApi().apply {
        if (resolvedRemoteRef != null) {
            remoteUrlAction = { _, remote -> "git@example.test:team/repo.git".takeIf { remote == "origin" } }
            remoteBranchExistsAction = { _, branch, remote -> branch == PARENT_BRANCH && remote == "origin" }
            isAncestorAction = { _, ancestor, descendant ->
                ancestor == "refs/heads/$PARENT_BRANCH" &&
                    descendant == "refs/remotes/$resolvedRemoteRef"
            }
        }
        logAction = { path, args ->
            assertEquals(worktreePath, path)
            graph.mergeCommitsSelectedBy(args.toList())
        }
    }

    private fun assertSelectedOperation(
        git: FakeGitCommandApi,
        expectedMethod: String,
        expectedRef: String,
        worktreePath: String = WORKTREE_PATH,
    ) {
        val integrationCalls = git.calls.filter { it.method == "rebase" || it.method == "merge" }
        assertEquals(
            listOf(FakeGitCommandApi.Call(expectedMethod, listOf(worktreePath, expectedRef))),
            integrationCalls,
        )
        assertEquals(1, git.calls.count { it.method == "remoteUrl" })
        assertEquals(
            listOf(
                FakeGitCommandApi.Call(
                    "log",
                    listOf(worktreePath, "--merges", "--format=%H", "$expectedRef..HEAD"),
                ),
            ),
            git.calls.filter { it.method == "log" },
        )
    }

    private fun conflictFailure(operation: String): GitCommandException = GitCommandException(
        command = listOf("git", operation),
        exitCode = 1,
        gitOutput = "CONFLICT (content): Merge conflict",
    )

    private data class GraphCommit(
        val hash: String,
        val parents: List<String>,
    )

    private class CommitGraph(
        commits: List<GraphCommit>,
        private val refs: Map<String, String>,
    ) {
        private val commitsByHash = commits.associateBy(GraphCommit::hash)

        fun mergeCommitsSelectedBy(arguments: List<String>): String {
            val range = arguments.firstOrNull { it.contains("..") }
            val selectedHashes = if (range == null) {
                reachableFrom(refs.getValue("HEAD"))
            } else {
                val (excludedRef, includedRef) = range.split("..", limit = 2)
                reachableFrom(refs.getValue(includedRef)) - reachableFrom(refs.getValue(excludedRef))
            }
            return selectedHashes
                .mapNotNull(commitsByHash::get)
                .filter { it.parents.size > 1 }
                .joinToString("\n", transform = GraphCommit::hash)
        }

        private fun reachableFrom(startHash: String): Set<String> {
            val reachable = mutableSetOf<String>()
            val pending = ArrayDeque<String>().apply { add(startHash) }
            while (pending.isNotEmpty()) {
                val hash = pending.removeFirst()
                if (reachable.add(hash)) commitsByHash.getValue(hash).parents.forEach(pending::add)
            }
            return reachable
        }

        companion object {
            fun emptyChildHistory(): CommitGraph = CommitGraph(
                commits = listOf(GraphCommit("base", emptyList())),
                refs = mapOf(PARENT_BRANCH to "base", "HEAD" to "base"),
            )

            fun linearChildHistory(): CommitGraph = CommitGraph(
                commits = listOf(
                    GraphCommit("base", emptyList()),
                    GraphCommit("child-1", listOf("base")),
                    GraphCommit("child-2", listOf("child-1")),
                ),
                refs = mapOf(PARENT_BRANCH to "base", "HEAD" to "child-2"),
            )

            fun childOnlyMergeHistory(baseRef: String): CommitGraph = CommitGraph(
                commits = listOf(
                    GraphCommit("base", emptyList()),
                    GraphCommit("child", listOf("base")),
                    GraphCommit("side", listOf("base")),
                    GraphCommit("child-merge", listOf("child", "side")),
                ),
                refs = mapOf(baseRef to "base", "HEAD" to "child-merge"),
            )

            fun inheritedBaseMergeHistory(): CommitGraph = CommitGraph(
                commits = listOf(
                    GraphCommit("root", emptyList()),
                    GraphCommit("base-main", listOf("root")),
                    GraphCommit("base-side", listOf("root")),
                    GraphCommit("base-merge", listOf("base-main", "base-side")),
                    GraphCommit("child", listOf("base-merge")),
                ),
                refs = mapOf(PARENT_BRANCH to "base-merge", "HEAD" to "child"),
            )
        }
    }

    private companion object {
        const val WORKTREE_PATH = "/repos/dev-lake-utils-feature-login"
        const val PARENT_BRANCH = "main"
    }
}
