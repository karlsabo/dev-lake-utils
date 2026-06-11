package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorktreeServiceCreateTest {
    @Test
    fun createBranchWorktree_fromBranchBase_createsDerivedPathFromExplicitBase() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr"
        val baseBranch = "feature/base-pr"
        val targetBranch = "feature/stacked-pr"
        val expectedWorktreePath = buildWorktreePath(repoPath, targetBranch).value
        val service = GitWorktreeService(fake)

        val result = service.createBranchWorktree(
            repoPath,
            baseWorktreePath,
            baseBranch,
            targetBranch,
        )

        assertEquals(expectedWorktreePath, result)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call(
                    "worktreeAddNewBranch",
                    listOf(baseWorktreePath, targetBranch, expectedWorktreePath, baseBranch),
                ),
            ),
            fake.calls.filter { it.method == "worktreeAddNewBranch" },
        )
        assertTrue(fake.calls.none { it.method == "fetch" })
        assertTrue(fake.calls.none { it.method == "worktreeAdd" })
    }

    @Test
    fun createBranchWorktree_exactExistingTargetWorktreeReturnsPathWithoutCreating() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val baseWorktreePath = "/repos/dev-lake-utils-feature-base-pr"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(repoPath, targetBranch).value
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree $targetWorktreePath
            HEAD def456
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        fake.remoteBranchExistsAction = { _, _, _ -> true }
        val service = GitWorktreeService(fake)

        val result = service.createBranchWorktree(
            repoPath,
            baseWorktreePath,
            "feature/base-pr",
            targetBranch,
        )

        assertEquals(targetWorktreePath, result)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", targetBranch)),
                FakeGitCommandApi.Call("worktreeList", listOf(repoPath)),
            ),
            fake.calls,
        )
    }

    @Test
    fun planBranchWorktreeCreation_existingLocalBranchWithoutWorktreeReportsExistingBranchPlan() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(repoPath, targetBranch).value
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main
        """.trimIndent()
        fake.localBranchExistsAction = { path, branch -> path == repoPath && branch == targetBranch }
        val service = GitWorktreeService(fake)

        val plan = service.planBranchWorktreeCreation(repoPath, targetBranch)

        assertEquals(
            BranchWorktreeCreationPlan.UseExistingLocalBranch(
                targetBranch = targetBranch,
                worktreePath = targetWorktreePath,
            ),
            plan,
        )
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", targetBranch)),
                FakeGitCommandApi.Call("worktreeList", listOf(repoPath)),
                FakeGitCommandApi.Call("localBranchExists", listOf(repoPath, targetBranch)),
            ),
            fake.calls,
        )
    }

    @Test
    fun createBranchWorktree_existingLocalBranchWithoutWorktreeFailsBeforeCreatingNewBranch() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val targetBranch = "feature/stacked-pr"
        val targetWorktreePath = buildWorktreePath(repoPath, targetBranch).value
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main
        """.trimIndent()
        fake.localBranchExistsAction = { _, _ -> true }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                repoPath,
                "/repos/dev-lake-utils-feature-base-pr",
                "feature/base-pr",
                targetBranch,
            )
        }

        assertEquals(
            "Local branch feature/stacked-pr already exists without a worktree at $targetWorktreePath.",
            ex.message,
        )
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", targetBranch)),
                FakeGitCommandApi.Call("worktreeList", listOf(repoPath)),
                FakeGitCommandApi.Call("localBranchExists", listOf(repoPath, targetBranch)),
            ),
            fake.calls,
        )
        assertTrue(fake.calls.none { it.method == "remoteBranchExists" })
        assertTrue(fake.calls.none { it.method == "worktreeAddNewBranch" })
    }

    @Test
    fun createBranchWorktree_targetBranchCheckedOutElsewhereFailsBeforeWorktreeCommand() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val targetBranch = "feature/stacked-pr"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-stacked-pr-existing
            HEAD def456
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                repoPath,
                "/repos/dev-lake-utils-feature-base-pr",
                "feature/base-pr",
                targetBranch,
            )
        }

        assertTrue(ex.message!!.contains("Branch feature/stacked-pr is already checked out elsewhere"))
        assertTrue(ex.message!!.contains("/repos/dev-lake-utils-stacked-pr-existing"))
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", targetBranch)),
                FakeGitCommandApi.Call("worktreeList", listOf(repoPath)),
            ),
            fake.calls,
        )
    }

    @Test
    fun createBranchWorktree_remoteTargetBranchExistsFailsBeforeWorktreeCommand() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val targetBranch = "feature/stacked-pr"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main
        """.trimIndent()
        fake.remoteBranchExistsAction = { path, branch, remote ->
            path == repoPath && branch == targetBranch && remote == "origin"
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                repoPath,
                "/repos/dev-lake-utils-feature-base-pr",
                "feature/base-pr",
                targetBranch,
            )
        }

        assertEquals(
            "Remote branch origin/feature/stacked-pr already exists. Choose a different branch name.",
            ex.message,
        )
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", targetBranch)),
                FakeGitCommandApi.Call("worktreeList", listOf(repoPath)),
                FakeGitCommandApi.Call("localBranchExists", listOf(repoPath, targetBranch)),
                FakeGitCommandApi.Call("remoteBranchExists", listOf(repoPath, targetBranch, "origin")),
            ),
            fake.calls,
        )
        assertTrue(fake.calls.none { it.method == "worktreeAddNewBranch" })
    }

    @Test
    fun createBranchWorktree_remoteBranchCheckFailureFailsBeforeWorktreeCommand() {
        val fake = FakeGitCommandApi()
        fake.remoteBranchExistsAction = { _, branch, _ ->
            throw GitCommandException(
                command = listOf("git", "ls-remote", "--exit-code", "--heads", "origin", "refs/heads/$branch"),
                exitCode = 128,
                gitOutput = "fatal: not a git repository",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                "/tmp/repo",
                "/tmp/repo-feature-base-pr",
                "feature/base-pr",
                "feature/stacked-pr",
            )
        }

        assertTrue(ex.message!!.contains("Failed to check remote branch origin/feature/stacked-pr"))
        assertTrue(ex.cause is GitCommandException)
        assertTrue(fake.calls.none { it.method == "worktreeAddNewBranch" })
    }

    @Test
    fun createBranchWorktree_invalidTargetBranchFailsBeforeWorktreeCommand() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                "/tmp/repo",
                "/tmp/repo-feature-base-pr",
                "feature/base-pr",
                "feature/new dashboard",
            )
        }

        assertTrue(ex.message!!.contains("Invalid worktree branch name"))
        assertTrue(fake.calls.none { it.method == "worktreeAddNewBranch" })
    }

    @Test
    fun createBranchWorktree_worktreeAddFailure_throwsWorktreeException() {
        val fake = FakeGitCommandApi()
        fake.worktreeAddNewBranchAction = { _, _, _, _ ->
            throw GitCommandException(
                command = listOf("git", "worktree", "add", "-b"),
                exitCode = 128,
                gitOutput = "fatal: cannot add",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.createBranchWorktree(
                "/tmp/repo",
                "/tmp/repo-feature-base-pr",
                "feature/base-pr",
                "feature/stacked-pr",
            )
        }

        assertTrue(ex.message!!.contains("Failed to create worktree"))
        assertTrue(ex.message!!.contains("from feature/base-pr"))
        assertTrue(ex.cause is GitCommandException)
    }

    @Test
    fun isBranchAncestor_returnsTrueWhenBaseTipIsAncestorOfChildTip() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        fake.isAncestorAction = { _, _, _ -> true }

        assertTrue(
            service.isBranchAncestor(
                "/repos/dev-lake-utils",
                "feature/base-pr",
                "feature/stacked-pr",
            ),
        )
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/base-pr")),
                FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature/stacked-pr")),
                FakeGitCommandApi.Call(
                    "isAncestor",
                    listOf(
                        "/repos/dev-lake-utils",
                        "refs/heads/feature/base-pr",
                        "refs/heads/feature/stacked-pr",
                    ),
                ),
            ),
            fake.calls,
        )
    }

    @Test
    fun isBranchAncestor_returnsFalseWhenBaseTipIsNotAncestorOfChildTip() {
        val fake = FakeGitCommandApi()
        fake.isAncestorAction = { _, _, _ -> false }
        val service = GitWorktreeService(fake)

        assertFalse(
            service.isBranchAncestor(
                "/repos/dev-lake-utils",
                "feature/base-pr",
                "feature/stacked-pr",
            ),
        )
    }

    @Test
    fun isBranchAncestor_gitFailureThrowsWorktreeException() {
        val fake = FakeGitCommandApi()
        fake.isAncestorAction = { _, _, _ ->
            throw GitCommandException(
                command = listOf("git", "merge-base", "--is-ancestor"),
                exitCode = 128,
                gitOutput = "fatal: bad revision 'refs/heads/feature/missing'",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.isBranchAncestor(
                "/repos/dev-lake-utils",
                "feature/base-pr",
                "feature/stacked-pr",
            )
        }

        val expectedMessage = "Failed to check whether feature/base-pr is an ancestor of feature/stacked-pr"
        assertTrue(ex.message!!.contains(expectedMessage))
        assertTrue(ex.cause is GitCommandException)
    }
}
