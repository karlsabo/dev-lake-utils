package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [GitCommandService] that run real git commands.
 * A temporary bare repo is created per test to keep them isolated and fast.
 */
class GitCommandServiceTest {

    private val service = GitCommandService()

    private fun createTempDir(prefix: String): String {
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

    private fun initBareRepo(path: String) {
        val result = executeCommand(listOf("git", "init", "--bare", path), workingDirectory = null)
        check(result.exitCode == 0) { "Failed to init bare repo: ${result.stderr}" }
    }

    private fun initRepoWithCommit(path: String): String {
        executeCommand(listOf("git", "init", path), workingDirectory = null)
        executeCommand(listOf("git", "-C", path, "config", "user.email", "test@test.com"), workingDirectory = null)
        executeCommand(listOf("git", "-C", path, "config", "user.name", "Test"), workingDirectory = null)
        // Create an initial commit so we have a valid HEAD
        executeCommand(listOf("sh", "-c", "echo hello > $path/README.md"), workingDirectory = null)
        executeCommand(listOf("git", "-C", path, "add", "."), workingDirectory = null)
        executeCommand(listOf("git", "-C", path, "commit", "-m", "initial"), workingDirectory = null)
        val hashResult = executeCommand(listOf("git", "-C", path, "rev-parse", "HEAD"), workingDirectory = null)
        return hashResult.stdout.trim()
    }

    // -- clone --

    @Test
    fun clone_createsRepo() {
        val bareDir = createTempDir("bare")
        val cloneDir = createTempDir("clone")
        removeTempDir(cloneDir) // clone wants a non-existent target
        try {
            initBareRepo(bareDir)
            service.clone(bareDir, cloneDir)
            assertTrue(service.isGitRepository(cloneDir))
        } finally {
            removeTempDir(bareDir)
            removeTempDir(cloneDir)
        }
    }

    @Test
    fun isGitRepository_trueForRepo() {
        val dir = createTempDir("repo")
        try {
            initRepoWithCommit(dir)
            assertTrue(service.isGitRepository(dir))
        } finally {
            removeTempDir(dir)
        }
    }

    @Test
    fun isGitRepository_falseForNonRepo() {
        val dir = createTempDir("notrepo")
        try {
            assertFalse(service.isGitRepository(dir))
        } finally {
            removeTempDir(dir)
        }
    }

    @Test
    fun fetch_runsWithoutError() {
        val originDir = createTempDir("origin")
        val cloneDir = createTempDir("clone")
        removeTempDir(cloneDir)
        try {
            initRepoWithCommit(originDir)
            service.clone(originDir, cloneDir)
            // fetch should not throw
            service.fetch(cloneDir)
        } finally {
            removeTempDir(originDir)
            removeTempDir(cloneDir)
        }
    }

    @Test
    fun worktree_addListRemove_lifecycle() {
        val repoDir = createTempDir("repo")
        val worktreeDir = createTempDir("wt")
        removeTempDir(worktreeDir)
        try {
            initRepoWithCommit(repoDir)
            // Create a branch for the worktree
            executeCommand(listOf("git", "-C", repoDir, "branch", "feature-branch"), workingDirectory = null)

            service.worktreeAdd(repoDir, worktreeDir, "feature-branch")

            val listing = service.worktreeList(repoDir)
            assertTrue(listing.contains(worktreeDir), "worktreeList should contain the new worktree path")

            service.worktreeRemove(repoDir, worktreeDir)

            val listingAfter = service.worktreeList(repoDir)
            assertFalse(listingAfter.contains(worktreeDir), "worktreeList should no longer contain the removed path")
        } finally {
            removeTempDir(repoDir)
            removeTempDir(worktreeDir)
        }
    }

    @Test
    fun clone_invalidUrl_throwsGitCommandException() {
        val target = createTempDir("fail")
        removeTempDir(target)
        try {
            val ex = assertFailsWith<GitCommandException> {
                service.clone("not-a-valid-url", target)
            }
            assertTrue(ex.exitCode != 0)
            assertTrue(ex.command.contains("clone"))
        } finally {
            removeTempDir(target)
        }
    }

    @Test
    fun execute_escapeHatch_returnsOutput() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            val output = service.execute(repoDir, "branch", "--list")
            assertTrue(output.contains("main") || output.contains("master"), "Should list at least one branch")
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun revParse_returnsHeadHash() {
        val repoDir = createTempDir("repo")
        try {
            val expectedHash = initRepoWithCommit(repoDir)
            val actual = service.revParse(repoDir, "HEAD")
            assertEquals(expectedHash, actual)
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun status_cleanRepo_isEmpty() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            val output = service.status(repoDir)
            assertTrue(output.isEmpty(), "Clean repo status should be empty, got: $output")
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun diff_cleanRepo_isEmpty() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            val output = service.diff(repoDir)
            assertTrue(output.isEmpty(), "Clean repo diff should be empty, got: $output")
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun log_returnsCommitInfo() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            val output = service.log(repoDir, "--oneline", "-1")
            assertTrue(output.contains("initial"), "Log should contain commit message")
        } finally {
            removeTempDir(repoDir)
        }
    }
}
