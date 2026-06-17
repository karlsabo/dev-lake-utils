package com.github.karlsabo.git

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitWorktreeServiceRebaseTest {
    @Test
    fun rebaseWorktreeOntoParent_runsRebaseInChildWorktreeWithAutostashCommand() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val service: GitWorktreeApi = GitWorktreeService(fake)

        service.rebaseWorktreeOntoParent(
            worktreePath = childWorktreePath,
            parentBranch = parentBranch,
        )

        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", parentBranch)),
                FakeGitCommandApi.Call("rebase", listOf(childWorktreePath, parentBranch)),
            ),
            fake.calls,
        )
    }

    @Test
    fun rebaseWorktreeOntoParent_classifiesFailureWithRebaseInProgressAsConflict() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = createArchiveWorktreeTempDir()
        val parentBranch = "feature/base-pr"
        val rebaseMergePath = Path(childWorktreePath, ".git", "rebase-merge")
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "rebase", "--autostash", parentBranch),
            exitCode = 1,
            gitOutput = "CONFLICT (content): Merge conflict",
        )
        fake.rebaseAction = { _, _ -> throw failure }
        fake.revParseAction = { _, args ->
            when (args.toList()) {
                listOf("--git-path", "rebase-merge") -> rebaseMergePath.toString()
                else -> ""
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        try {
            SystemFileSystem.createDirectories(rebaseMergePath)

            val ex = assertFailsWith<GitRebaseConflictException> {
                service.rebaseWorktreeOntoParent(
                    worktreePath = childWorktreePath,
                    parentBranch = parentBranch,
                )
            }

            assertEquals(
                "Rebase conflict while rebasing worktree $childWorktreePath onto $parentBranch",
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
    fun rebaseWorktreeOntoParent_wrapsGitCommandFailure() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val parentBranch = "feature/base-pr"
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "rebase", "--autostash", parentBranch),
            exitCode = 1,
            gitOutput = "conflict",
        )
        fake.rebaseAction = { _, _ -> throw failure }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.rebaseWorktreeOntoParent(
                worktreePath = childWorktreePath,
                parentBranch = parentBranch,
            )
        }

        assertEquals(
            "Failed to rebase worktree $childWorktreePath onto $parentBranch: conflict",
            ex.message,
        )
        assertSame(failure, ex.cause)
    }

    @Test
    fun abortRebase_runsAbortInChildWorktree() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val service: GitWorktreeApi = GitWorktreeService(fake)

        service.abortRebase(childWorktreePath)

        assertEquals(
            listOf(FakeGitCommandApi.Call("abortRebase", listOf(childWorktreePath))),
            fake.calls,
        )
    }

    @Test
    fun abortRebase_wrapsGitCommandFailure() {
        val fake = FakeGitCommandApi()
        val childWorktreePath = "/repos/dev-lake-utils-feature-stacked-pr"
        val failure = GitCommandException(
            command = listOf("git", "-C", childWorktreePath, "rebase", "--abort"),
            exitCode = 128,
            gitOutput = "fatal: no rebase in progress",
        )
        fake.abortRebaseAction = { throw failure }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.abortRebase(childWorktreePath)
        }

        assertEquals(
            "Failed to abort rebase in worktree $childWorktreePath: fatal: no rebase in progress",
            ex.message,
        )
        assertSame(failure, ex.cause)
    }

    @Test
    fun branchNeedsRebase_returnsTrueWhenParentHasCommitsNotContainedInChild() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.hasCommitsNotContainedInAction = { path, sourceRef, containingRef ->
            path == repoPath &&
                sourceRef == "refs/heads/feature/base-pr" &&
                containingRef == "refs/heads/feature/stacked-pr"
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val needsRebase = service.branchNeedsRebase(
            repoPath = repoPath,
            parentBranch = "feature/base-pr",
            childBranch = "feature/stacked-pr",
        )

        assertTrue(needsRebase)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/stacked-pr")),
                FakeGitCommandApi.Call(
                    "hasCommitsNotContainedIn",
                    listOf(repoPath, "refs/heads/feature/base-pr", "refs/heads/feature/stacked-pr"),
                ),
            ),
            fake.calls,
        )
    }

    @Test
    fun branchNeedsRebase_wrapsGitCommandFailure() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val failure = GitCommandException(
            command = listOf("git", "rev-list", "--max-count=1"),
            exitCode = 128,
            gitOutput = "fatal: bad revision",
        )
        fake.hasCommitsNotContainedInAction = { _, _, _ -> throw failure }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.branchNeedsRebase(
                repoPath = repoPath,
                parentBranch = "feature/base-pr",
                childBranch = "feature/stacked-pr",
            )
        }

        assertEquals(
            "Failed to check whether feature/base-pr has commits not contained in " +
                "feature/stacked-pr: fatal: bad revision",
            ex.message,
        )
        assertSame(failure, ex.cause)
    }
}
