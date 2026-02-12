package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A fake [GitCommandApi] that records calls and lets tests configure results.
 */
private class FakeGitCommandApi : GitCommandApi {

    data class Call(val method: String, val args: List<String> = emptyList())

    val calls = mutableListOf<Call>()

    var cloneAction: (String, String) -> Unit = { _, _ -> }
    var isGitRepositoryResult: Boolean = true
    var fetchAction: (String, String, Array<out String>) -> Unit = { _, _, _ -> }
    var worktreeAddAction: (String, String, String) -> Unit = { _, _, _ -> }
    var worktreeListResult: String = ""
    var worktreeListAction: (String) -> String = { worktreeListResult }
    var worktreeRemoveAction: (String, String) -> Unit = { _, _ -> }
    var executeAction: (String?, Array<out String>) -> String = { _, _ -> "" }

    override fun clone(url: String, targetPath: String) {
        calls.add(Call("clone", listOf(url, targetPath)))
        cloneAction(url, targetPath)
    }

    override fun isGitRepository(repoPath: String): Boolean {
        calls.add(Call("isGitRepository", listOf(repoPath)))
        return isGitRepositoryResult
    }

    override fun fetch(repoPath: String, remote: String, vararg refSpecs: String) {
        calls.add(Call("fetch", listOf(repoPath, remote) + refSpecs.toList()))
        fetchAction(repoPath, remote, refSpecs)
    }

    override fun worktreeAdd(repoPath: String, path: String, commitIsh: String) {
        calls.add(Call("worktreeAdd", listOf(repoPath, path, commitIsh)))
        worktreeAddAction(repoPath, path, commitIsh)
    }

    override fun worktreeList(repoPath: String): String {
        calls.add(Call("worktreeList", listOf(repoPath)))
        return worktreeListAction(repoPath)
    }

    override fun worktreeRemove(repoPath: String, path: String) {
        calls.add(Call("worktreeRemove", listOf(repoPath, path)))
        worktreeRemoveAction(repoPath, path)
    }

    override fun checkout(repoPath: String, ref: String) {
        calls.add(Call("checkout", listOf(repoPath, ref)))
    }

    override fun status(repoPath: String): String {
        calls.add(Call("status", listOf(repoPath)))
        return ""
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
        return ""
    }

    override fun execute(repoPath: String?, vararg args: String): String {
        calls.add(Call("execute", listOfNotNull(repoPath) + args.toList()))
        return executeAction(repoPath, args)
    }
}

class GitWorktreeServiceTest {
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
        val expectedWorktreePath = GitWorktreeService.buildWorktreePath(repoPath, branch)

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
        assertEquals(GitWorktreeService.buildWorktreePath(repoPath, branch), result)

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

    @Test
    fun listWorktrees_parsesOutput() {
        val fake = FakeGitCommandApi()
        fake.worktreeListResult = """
            worktree /tmp/repo
            HEAD abc123
            branch refs/heads/main

            worktree /tmp/repo-feature
            HEAD def456
            branch refs/heads/feature/x
        """.trimIndent()

        val service = GitWorktreeService(fake)
        val worktrees = service.listWorktrees("/tmp/repo")

        assertEquals(2, worktrees.size)
        assertEquals("/tmp/repo", worktrees[0].path)
        assertEquals("main", worktrees[0].branch)
        assertEquals("/tmp/repo-feature", worktrees[1].path)
        assertEquals("feature/x", worktrees[1].branch)
    }

    @Test
    fun listWorktrees_failure_throwsWorktreeException() {
        val fake = FakeGitCommandApi()
        fake.worktreeListAction = {
            throw GitCommandException(
                command = listOf("git", "worktree", "list"),
                exitCode = 128,
                gitOutput = "fatal: not a git repository",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.listWorktrees("/tmp/not-a-repo")
        }
        assertTrue(ex.message!!.contains("Failed to list worktrees"))
    }

    @Test
    fun removeWorktree_success() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        service.removeWorktree("/tmp/repo-feature")

        val executeCall = fake.calls.find { it.method == "execute" }
        assertEquals(listOf("worktree", "remove", "/tmp/repo-feature"), executeCall?.args)
    }

    @Test
    fun removeWorktree_failure_throwsWorktreeException() {
        val fake = FakeGitCommandApi()
        fake.executeAction = { _, _ ->
            throw GitCommandException(
                command = listOf("git", "worktree", "remove"),
                exitCode = 128,
                gitOutput = "fatal: cannot remove",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.removeWorktree("/tmp/repo-feature")
        }
        assertTrue(ex.message!!.contains("Failed to remove worktree"))
    }
}
