package com.github.karlsabo.git

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun listWorktrees_marksDirtyStatusFromGitStatus() {
        val fake = FakeGitCommandApi()
        fake.worktreeListResult = """
            worktree /tmp/repo
            HEAD abc123
            branch refs/heads/main

            worktree /tmp/repo-feature
            HEAD def456
            branch refs/heads/feature/x
        """.trimIndent()
        fake.statusAction = { path ->
            when (path) {
                "/tmp/repo" -> ""
                "/tmp/repo-feature" -> " M README.md"
                else -> error("Unexpected status path $path")
            }
        }

        val service = GitWorktreeService(fake)
        val worktrees = service.listWorktrees("/tmp/repo")

        assertEquals(false, worktrees[0].isDirty)
        assertEquals(true, worktrees[1].isDirty)
        assertEquals(
            listOf("/tmp/repo", "/tmp/repo-feature"),
            fake.calls.filter { it.method == "status" }.map { it.args.single() },
        )
    }

    @Test
    fun listWorktrees_statusFailure_marksOnlyThatWorktreeDirty() {
        val fake = FakeGitCommandApi()
        fake.worktreeListResult = """
            worktree /tmp/repo
            HEAD abc123
            branch refs/heads/main

            worktree /tmp/repo-feature
            HEAD def456
            branch refs/heads/feature/x
        """.trimIndent()
        fake.statusAction = { path ->
            when (path) {
                "/tmp/repo" -> ""
                "/tmp/repo-feature" -> throw GitCommandException(
                    command = listOf("git", "status"),
                    exitCode = 128,
                    gitOutput = "fatal: not a git repository",
                )

                else -> error("Unexpected status path $path")
            }
        }
        val service = GitWorktreeService(fake)

        val worktrees = service.listWorktrees("/tmp/repo")

        assertEquals(2, worktrees.size)
        assertEquals(false, worktrees[0].isDirty)
        assertEquals(true, worktrees[1].isDirty)
        assertEquals(
            listOf("/tmp/repo", "/tmp/repo-feature"),
            fake.calls.filter { it.method == "status" }.map { it.args.single() },
        )
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
    fun resolveRepositoryRoot_returnsMainRootForLinkedWorktree() {
        val fake = FakeGitCommandApi()
        fake.revParseAction = { _, args ->
            assertEquals(listOf("--show-toplevel"), args.toList())
            "/repos/dev-lake-utils-feature-worktree-panel"
        }
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-worktree-panel
            HEAD def456
            branch refs/heads/feature/worktree-panel
        """.trimIndent()
        val service = GitWorktreeService(fake)

        val repositoryWorktrees = service.resolveRepositoryRoot(
            "/repos/dev-lake-utils-feature-worktree-panel",
        )

        assertEquals("/repos/dev-lake-utils", repositoryWorktrees.rootPath)
        assertEquals(
            "/repos/dev-lake-utils-feature-worktree-panel",
            repositoryWorktrees.selectedWorktreePath,
        )
        assertEquals(listOf("main", "feature/worktree-panel"), repositoryWorktrees.worktrees.map { it.branch })
    }

    @Test
    fun removeWorktree_success() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        service.removeWorktree("/tmp/repo-feature", force = false)

        val executeCall = fake.calls.find { it.method == "execute" }
        assertEquals(listOf("worktree", "remove", "/tmp/repo-feature"), executeCall?.args)
    }

    @Test
    fun removeWorktree_forceUsesForceFlag() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)

        service.removeWorktree("/tmp/repo-feature", force = true)

        val executeCall = fake.calls.find { it.method == "execute" }
        assertEquals(listOf("worktree", "remove", "--force", "/tmp/repo-feature"), executeCall?.args)
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
            service.removeWorktree("/tmp/repo-feature", force = false)
        }
        assertTrue(ex.message!!.contains("Failed to remove worktree"))
    }

    @Test
    fun archiveWorktree_removesLeftoverDirectoryAndPrunes() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)
        val worktreePath = createTempDir("repo-feature")
        SystemFileSystem.sink(Path(worktreePath, "leftover.txt")).buffered().use { sink ->
            sink.writeString("leftover")
        }

        service.archiveWorktree("/tmp/repo", worktreePath, force = false)

        assertFalse(SystemFileSystem.exists(Path(worktreePath)))
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("worktreeRemove", listOf("/tmp/repo", worktreePath)),
                FakeGitCommandApi.Call("execute", listOf("/tmp/repo", "worktree", "prune")),
            ),
            fake.calls.filter { it.method == "worktreeRemove" || it.method == "execute" },
        )
    }

    @Test
    fun archiveWorktree_forceRemovesLeftoverDirectoryAndPrunes() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(fake)
        val worktreePath = createTempDir("repo-feature")
        SystemFileSystem.sink(Path(worktreePath, "leftover.txt")).buffered().use { sink ->
            sink.writeString("leftover")
        }

        service.archiveWorktree("/tmp/repo", worktreePath, force = true)

        assertFalse(SystemFileSystem.exists(Path(worktreePath)))
        assertEquals(
            listOf(
                FakeGitCommandApi.Call(
                    "execute",
                    listOf("/tmp/repo", "worktree", "remove", "--force", worktreePath),
                ),
                FakeGitCommandApi.Call("execute", listOf("/tmp/repo", "worktree", "prune")),
            ),
            fake.calls.filter { it.method == "worktreeRemove" || it.method == "execute" },
        )
    }

    @Test
    fun archiveWorktree_deleteFailureStillPrunes() {
        val fake = FakeGitCommandApi()
        val service = GitWorktreeService(
            gitCommandApi = fake,
            deleteCheckoutDirectory = {
                throw IllegalStateException("delete failed")
            },
        )
        val worktreePath = "/tmp/repo-feature"

        val ex = assertFailsWith<GitWorktreeException> {
            service.archiveWorktree("/tmp/repo", worktreePath, force = false)
        }

        assertTrue(ex.message!!.contains("Failed to delete leftover worktree directory"))
        assertEquals("delete failed", ex.cause?.message)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("worktreeRemove", listOf("/tmp/repo", worktreePath)),
                FakeGitCommandApi.Call("execute", listOf("/tmp/repo", "worktree", "prune")),
            ),
            fake.calls.filter { it.method == "worktreeRemove" || it.method == "execute" },
        )
    }

    @Test
    fun archiveWorktree_removeFailureDoesNotDeleteOrPrune() {
        val fake = FakeGitCommandApi()
        val worktreePath = createTempDir("repo-feature")
        fake.worktreeRemoveAction = { _, path ->
            if (path != worktreePath) error("Unexpected worktree path $path")
            throw GitCommandException(
                command = listOf("git", "worktree", "remove"),
                exitCode = 128,
                gitOutput = "fatal: contains modified files",
            )
        }
        val service = GitWorktreeService(fake)

        val ex = assertFailsWith<GitWorktreeException> {
            service.archiveWorktree("/tmp/repo", worktreePath, force = false)
        }

        assertTrue(ex.message!!.contains("Failed to remove worktree"))
        assertTrue(SystemFileSystem.exists(Path(worktreePath)))
        assertEquals(
            listOf(FakeGitCommandApi.Call("worktreeRemove", listOf("/tmp/repo", worktreePath))),
            fake.calls.filter { it.method == "worktreeRemove" || it.method == "execute" },
        )
        removeTempDir(worktreePath)
    }
}

private fun createTempDir(@Suppress("SameParameterValue") prefix: String): String {
    val dirName = "$prefix-${Random.nextLong().toULong().toString(16)}"
    val path = Path(SystemTemporaryDirectory, dirName)
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

private fun removeTempDir(path: String) {
    val root = Path(path)
    if (!SystemFileSystem.exists(root)) return
    deleteRecursively(root)
}

private fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}
