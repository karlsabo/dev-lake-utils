package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitWorktreeServiceBaseUpdateTest {
    @Test
    fun updateFastForwardsWhenRemoteIsAhead() {
        val git = FakeGitCommandApi().apply {
            queryRemoteDefaultBranchAction = { _, _ -> "main" }
            revParseAction = { _, _ -> "main" }
            isAncestorAction = { _, ancestorRef, descendantRef ->
                ancestorRef == "refs/heads/main" && descendantRef == "refs/remotes/origin/main"
            }
        }
        val service = GitWorktreeService(git)

        service.updateWorktreeFromOrigin("/repo-main", "main")

        assertEquals(
            listOf(
                FakeGitCommandApi.Call("fetch", listOf("/repo-main", "origin")),
                FakeGitCommandApi.Call("queryRemoteDefaultBranch", listOf("/repo-main", "origin")),
                FakeGitCommandApi.Call("revParse", listOf("/repo-main", "--abbrev-ref", "HEAD")),
                FakeGitCommandApi.Call(
                    "isAncestor",
                    listOf("/repo-main", "refs/heads/main", "refs/remotes/origin/main"),
                ),
                FakeGitCommandApi.Call("mergeFastForwardOnly", listOf("/repo-main", "origin/main")),
            ),
            git.calls.filter {
                it.method in setOf(
                    "fetch",
                    "queryRemoteDefaultBranch",
                    "revParse",
                    "isAncestor",
                    "mergeFastForwardOnly",
                )
            },
        )
    }

    @Test
    fun updateSucceedsWhenLocalAndRemoteAreEqual() {
        val git = updateReadyGit(remoteContainsLocal = true)
        val service = GitWorktreeService(git)

        service.updateWorktreeFromOrigin("/repo-main", "main")

        assertTrue(git.calls.any { it.method == "mergeFastForwardOnly" })
    }

    @Test
    fun updateStopsAfterFetchFailureAndPreservesGitOutput() {
        val worktreePath = "/repo-main"
        val branch = "main"
        val gitFailure = GitCommandException(
            command = listOf("git", "-C", worktreePath, "fetch", "origin"),
            exitCode = 128,
            gitOutput = "fatal: Authentication failed for 'https://github.com/example/repo.git/'",
        )
        val git = FakeGitCommandApi().apply {
            fetchAction = { _, _, _ -> throw gitFailure }
        }
        val service = GitWorktreeService(git)

        val failure = assertFailsWith<BaseBranchOriginFetchFailureException> {
            service.updateWorktreeFromOrigin(worktreePath, branch)
        }

        assertEquals(worktreePath, failure.worktreePath)
        assertEquals(branch, failure.branch)
        assertEquals(gitFailure.gitOutput, failure.gitOutput)
        assertSame(gitFailure, failure.cause)
        assertEquals(
            "Failed to fetch origin before updating branch $branch in worktree $worktreePath. " +
                "Resolve the fetch failure and try again: ${gitFailure.gitOutput}",
            failure.message,
        )
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", branch)),
                FakeGitCommandApi.Call("fetch", listOf(worktreePath, "origin")),
            ),
            git.calls,
        )
    }

    @Test
    fun updateRejectsLocalAheadHistoryWithoutMerging() {
        assertUnpublishedHistoryRejected(updateReadyGit(remoteContainsLocal = false))
    }

    @Test
    fun updateRejectsDivergedHistoryWithoutMerging() {
        assertUnpublishedHistoryRejected(updateReadyGit(remoteContainsLocal = false))
    }

    @Test
    fun updateSurfacesAutostashRestorationConflict() {
        val git = FakeGitCommandApi().apply {
            queryRemoteDefaultBranchAction = { _, _ -> "main" }
            revParseAction = { _, _ -> "main" }
            isAncestorAction = { _, _, _ -> true }
            mergeFastForwardOnlyAction = { repoPath, sourceRef ->
                throw GitCommandException(
                    command = listOf("git", "-C", repoPath, "merge", sourceRef),
                    exitCode = 1,
                    gitOutput = "Autostash restoration left conflicts in: README.md",
                )
            }
        }
        val service = GitWorktreeService(git)

        val failure = assertFailsWith<GitWorktreeException> {
            service.updateWorktreeFromOrigin("/repo-main", "main")
        }

        assertTrue(failure.message.orEmpty().contains("Autostash restoration left conflicts in: README.md"))
    }

    @Test
    fun updateRejectsWorktreeThatNoLongerHasRequestedBranchCheckedOut() {
        val git = FakeGitCommandApi().apply {
            queryRemoteDefaultBranchAction = { _, _ -> "main" }
            revParseAction = { _, _ -> "feature/other" }
        }
        val service = GitWorktreeService(git)

        val failure = assertFailsWith<GitWorktreeException> {
            service.updateWorktreeFromOrigin("/repo-main", "main")
        }

        assertTrue(failure.message.orEmpty().contains("currently has feature/other checked out"))
        assertTrue(git.calls.none { it.method == "mergeFastForwardOnly" })
    }

    @Test
    fun updateRejectsStaleBranchCallbackWhenRemoteDefaultChangedAfterRendering() {
        val git = FakeGitCommandApi().apply {
            queryRemoteDefaultBranchAction = { _, _ -> "trunk" }
        }
        val service = GitWorktreeService(git)

        val failure = assertFailsWith<GitWorktreeException> {
            service.updateWorktreeFromOrigin("/repo-main", "main")
        }

        assertTrue(failure.message.orEmpty().contains("origin's default branch is trunk"))
        assertTrue(git.calls.none { it.method == "mergeFastForwardOnly" })
    }

    private fun updateReadyGit(remoteContainsLocal: Boolean) = FakeGitCommandApi().apply {
        queryRemoteDefaultBranchAction = { _, _ -> "main" }
        revParseAction = { _, _ -> "main" }
        isAncestorAction = { _, _, _ -> remoteContainsLocal }
    }

    private fun assertUnpublishedHistoryRejected(git: FakeGitCommandApi) {
        val service = GitWorktreeService(git)

        val failure = assertFailsWith<GitWorktreeException> {
            service.updateWorktreeFromOrigin("/repo-main", "main")
        }

        assertEquals(
            "Cannot update local branch main from origin/main because main contains commits absent from origin/main. " +
                "Reconcile main with origin/main manually before updating.",
            failure.message,
        )
        assertTrue(git.calls.none { it.method == "mergeFastForwardOnly" })
    }
}
