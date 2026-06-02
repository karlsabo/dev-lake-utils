package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitWorktreeServiceEnsureTest {
    @Test
    fun ensureRepository_existingDirectory_isRepo() {
        // We can't easily fake SystemFileSystem.exists, so we test the isGitRepository path
        // by using a path that exists on the filesystem (the project root).
        val fake = FakeGitCommandApi()
        fake.isGitRepositoryResult = true
        val service = GitWorktreeService(fake)

        // Use the current working directory which definitely exists
        service.ensureRepository(".", "https://example.com/repo.git")

        // Should have checked isGitRepository, should NOT have cloned
        assertTrue(fake.calls.any { it.method == "isGitRepository" })
        assertTrue(fake.calls.none { it.method == "clone" })
    }

    @Test
    fun ensureRepository_existingDirectory_notARepo_throws() {
        val fake = FakeGitCommandApi()
        fake.isGitRepositoryResult = false
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.ensureRepository(".", "https://example.com/repo.git")
        }
        assertTrue(ex.message!!.contains("not a git repository"))
    }

    @Test
    fun ensureWorktree_alreadyExists_returnsPath() {
        val fake = FakeGitCommandApi()
        val repoPath = "/tmp/repo"
        val branch = "feature/test"
        val expectedWorktreePath = buildWorktreePath(repoPath, branch).value

        // Return porcelain output that includes the expected worktree path
        fake.worktreeListResult = "worktree $expectedWorktreePath\nHEAD abc123\nbranch refs/heads/feature/test\n"
        val service = GitWorktreeService(fake)

        val result = service.ensureWorktree(repoPath, branch)
        assertEquals(expectedWorktreePath, result)

        // Should not have called fetch or worktreeAdd
        assertTrue(fake.calls.none { it.method == "fetch" })
        assertTrue(fake.calls.none { it.method == "worktreeAdd" })
    }

    @Test
    fun ensureWorktree_invalidBranchWithWhitespaceFailsBeforeWorktreeCommands() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.ensureWorktree("/tmp/repo", "feature/new dashboard")
        }

        assertTrue(ex.message!!.contains("Invalid worktree branch name"))
        assertEquals(
            emptyList(),
            fake.calls.filter { it.method == "worktreeList" || it.method == "fetch" || it.method == "worktreeAdd" },
        )
    }

    @Test
    fun ensureWorktree_invalidGitRefFormatFailsBeforeWorktreeCommands() {
        val fake = FakeGitCommandApi()
        fake.executeAction = { _, args ->
            if (args.toList() == listOf("check-ref-format", "--branch", "feature//stacked-pr")) {
                throw GitCommandException(
                    command = listOf("git", "check-ref-format", "--branch", "feature//stacked-pr"),
                    exitCode = 1,
                    gitOutput = "fatal: 'feature//stacked-pr' is not a valid branch name",
                )
            }
            ""
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.ensureWorktree("/tmp/repo", "feature//stacked-pr")
        }

        assertTrue(ex.message!!.contains("Invalid worktree branch name"))
        assertEquals(
            listOf(FakeGitCommandApi.Call("execute", listOf("check-ref-format", "--branch", "feature//stacked-pr"))),
            fake.calls,
        )
    }

    @Test
    fun ensureWorktree_fetchFailure_isNonFatal() {
        val fake = FakeGitCommandApi()
        val repoPath = "/tmp/repo"
        val branch = "main"

        fake.worktreeListResult = "" // no existing worktrees
        fake.fetchAction = { _, _, _ ->
            throw GitCommandException(
                command = listOf("git", "fetch"),
                exitCode = 128,
                gitOutput = "fatal: could not fetch",
            )
        }
        // worktreeAdd should still be called and succeed
        val service = GitWorktreeService(fake)

        val result = service.ensureWorktree(repoPath, branch)
        assertEquals(buildWorktreePath(repoPath, branch).value, result)

        // worktreeAdd should still have been called despite fetch failure
        assertTrue(fake.calls.any { it.method == "worktreeAdd" })
    }

    @Test
    fun ensureWorktree_worktreeAddFailure_throwsWorktreeException() {
        val fake = FakeGitCommandApi()
        val repoPath = "/tmp/repo"
        val branch = "main"

        fake.worktreeListResult = ""
        fake.worktreeAddAction = { _, _, _ ->
            throw GitCommandException(
                command = listOf("git", "worktree", "add"),
                exitCode = 128,
                gitOutput = "fatal: cannot add",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.ensureWorktree(repoPath, branch)
        }
        assertTrue(ex.message!!.contains("Failed to create worktree"))
        assertTrue(ex.cause is GitCommandException)
    }
}
