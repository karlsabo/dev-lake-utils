package com.github.karlsabo.git

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
                FakeGitCommandApi.Call("remoteBranchExists", listOf(childWorktreePath, parentBranch, "origin")),
                FakeGitCommandApi.Call(
                    "fetch",
                    listOf(
                        childWorktreePath,
                        "origin",
                        "+refs/heads/$parentBranch:refs/remotes/origin/$parentBranch",
                    ),
                ),
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
    fun mergeWorktreeWithParent_mergesLocalParentWhenFetchedRemoteHasNoMatchingBranch() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        fake.remoteUrlAction = { _, remote ->
            "git@github.com:karlsabo/dev-lake-utils.git".takeIf { remote == "origin" }
        }
        fake.remoteBranchExistsAction = { _, _, _ -> false }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        service.mergeWorktreeWithParent(
            worktreePath = childWorktreePath,
            parentBranch = parentBranch,
        )

        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", parentBranch)),
                FakeGitCommandApi.Call("remoteUrl", listOf(childWorktreePath, "origin")),
                FakeGitCommandApi.Call("remoteBranchExists", listOf(childWorktreePath, parentBranch, "origin")),
                FakeGitCommandApi.Call("merge", listOf(childWorktreePath, parentBranch)),
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
    fun mergeWorktreeWithParent_classifiesFailureWithMergeInProgressAsConflict() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = createArchiveWorktreeTempDir()
        val parentBranch = "feature/base-pr"
        val mergeHeadPath = Path(childWorktreePath, ".git", "MERGE_HEAD")
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "merge", "--autostash", parentBranch),
            exitCode = 1,
            gitOutput = "CONFLICT (content): Merge conflict",
        )
        fake.mergeAction = { _, _ -> throw failure }
        fake.revParseAction = { _, args ->
            when (args.toList()) {
                listOf("--git-path", "MERGE_HEAD") -> mergeHeadPath.toString()
                else -> ""
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        try {
            SystemFileSystem.createDirectories(mergeHeadPath)

            val ex = assertFailsWith<GitMergeConflictException> {
                service.mergeWorktreeWithParent(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                )
            }

            assertEquals(
                "Merge conflict while merging $parentBranch into worktree $childWorktreePath",
                ex.message,
            )
            assertEquals(childWorktreePath, ex.worktreePath)
            assertEquals(parentBranch, ex.parentBranch)
            assertSame(failure, ex.cause)
        } finally {
            removeTempDir(childWorktreePath)
        }
    }

    @Test
    fun abortMerge_runsAbortInChildWorktree() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val service: GitWorktreeApi = GitWorktreeService(fake)

        service.abortMerge(childWorktreePath)

        assertEquals(
            listOf(FakeGitCommandApi.Call("abortMerge", listOf(childWorktreePath))),
            fake.calls,
        )
    }

    @Test
    fun abortMerge_wrapsGitCommandFailure() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "merge", "--abort"),
            exitCode = 128,
            gitOutput = "fatal: There is no merge to abort",
        )
        fake.abortMergeAction = { throw failure }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.abortMerge(childWorktreePath)
        }

        assertEquals(
            "Failed to abort merge in worktree $childWorktreePath: fatal: There is no merge to abort",
            ex.message,
        )
        assertSame(failure, ex.cause)
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
