package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitWorktreeServiceMergeTest {
    @Test
    fun mergeWorktreeWithParent_mergesFetchedRemoteParentWithAutostash() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        fake.remoteUrlAction = { _, remote ->
            "git@github.com:karlsabo/dev-lake-utils.git".takeIf { remote == "origin" }
        }
        fake.remoteBranchExistsAction = { _, branch, remote -> branch == parentBranch && remote == "origin" }
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            ancestorRef == "refs/heads/$parentBranch" &&
                descendantRef == "refs/remotes/origin/$parentBranch"
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        service.mergeWorktreeWithParent(
            worktreePath = childWorktreePath,
            parentBranch = parentBranch,
        )

        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", parentBranch)),
                FakeGitCommandApi.Call("remoteUrl", listOf(childWorktreePath, "origin")),
                FakeGitCommandApi.Call("fetch", listOf(childWorktreePath, "origin")),
                FakeGitCommandApi.Call("remoteBranchExists", listOf(childWorktreePath, parentBranch, "origin")),
                FakeGitCommandApi.Call(
                    "isAncestor",
                    listOf(childWorktreePath, "refs/heads/$parentBranch", "refs/remotes/origin/$parentBranch"),
                ),
                FakeGitCommandApi.Call("merge", listOf(childWorktreePath, "origin/$parentBranch")),
            ),
            fake.calls,
        )
    }

    @Test
    fun mergeWorktreeWithParent_rejectsDivergedParentWithoutMerging() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        fake.remoteUrlAction = { _, remote ->
            "git@github.com:karlsabo/dev-lake-utils.git".takeIf { remote == "origin" }
        }
        fake.remoteBranchExistsAction = { _, branch, remote -> branch == parentBranch && remote == "origin" }
        fake.isAncestorAction = { _, _, _ -> false }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<DivergedParentBranchException> {
            service.mergeWorktreeWithParent(
                worktreePath = childWorktreePath,
                parentBranch = parentBranch,
            )
        }

        assertEquals(
            "Local branch $parentBranch has diverged from origin/$parentBranch. " +
                "Reconcile $parentBranch with origin/$parentBranch before integrating it.",
            ex.message,
        )
        assertTrue(fake.calls.none { it.method == "merge" })
    }

    @Test
    fun mergeWorktreeWithParent_wrapsGitCommandFailure() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "merge", "--autostash", parentBranch),
            exitCode = 1,
            gitOutput = "CONFLICT (content): Merge conflict",
        )
        fake.mergeAction = { _, _ -> throw failure }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.mergeWorktreeWithParent(
                worktreePath = childWorktreePath,
                parentBranch = parentBranch,
            )
        }

        assertEquals(
            "Failed to merge $parentBranch into worktree $childWorktreePath: CONFLICT (content): Merge conflict",
            ex.message,
        )
        assertSame(failure, ex.cause)
    }
}
