package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
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

    private fun executeGit(vararg arguments: String): String {
        val command = listOf("git", *arguments)
        val result = executeCommand(command, workingDirectory = null)
        check(result.exitCode == 0) {
            "Fixture Git command failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}"
        }
        return result.stdout
    }

    private fun writeFixtureFile(
        repoDir: String,
        fileName: String,
        content: String,
    ) {
        SystemFileSystem.sink(Path(repoDir, fileName)).buffered().use { it.writeString(content) }
    }

    private fun initBareRepo(path: String) {
        executeGit("init", "--bare", path)
    }

    private fun initRepoWithCommit(path: String): String {
        executeGit("init", path)
        executeGit("-C", path, "config", "user.email", "test@test.com")
        executeGit("-C", path, "config", "user.name", "Test")
        writeFixtureFile(path, "README.md", "hello\n")
        executeGit("-C", path, "add", ".")
        executeGit("-C", path, "commit", "-m", "initial")
        return executeGit("-C", path, "rev-parse", "HEAD").trim()
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
    fun remoteBranchExists_queriesRemoteWithoutLocalTrackingRef() {
        val originDir = createTempDir("origin")
        val cloneDir = createTempDir("clone")
        removeTempDir(cloneDir)
        try {
            initRepoWithCommit(originDir)
            service.clone(originDir, cloneDir)
            executeGit("-C", originDir, "branch", "feature/stacked-pr")

            assertTrue(service.remoteBranchExists(cloneDir, "feature/stacked-pr"))
            assertFalse(service.remoteBranchExists(cloneDir, "feature/missing-pr"))
        } finally {
            removeTempDir(originDir)
            removeTempDir(cloneDir)
        }
    }

    @Test
    fun localBranchExists_detectsLocalBranchWithoutWorktree() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature/stacked-pr")

            assertTrue(service.localBranchExists(repoDir, "feature/stacked-pr"))
            assertFalse(service.localBranchExists(repoDir, "feature/missing-pr"))
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun currentBranchUpstreamRemote_readsConfiguredTrackingRemote() {
        val originDir = createTempDir("origin")
        val cloneDir = createTempDir("clone")
        removeTempDir(cloneDir)
        try {
            initRepoWithCommit(originDir)
            service.clone(originDir, cloneDir)

            assertEquals("origin", service.currentBranchUpstreamRemote(cloneDir))
        } finally {
            removeTempDir(originDir)
            removeTempDir(cloneDir)
        }
    }

    @Test
    fun remoteDefaultBranchRef_returnsRemoteHeadWhenAvailable() {
        val originDir = createTempDir("origin")
        val cloneDir = createTempDir("clone")
        removeTempDir(cloneDir)
        try {
            initRepoWithCommit(originDir)
            service.clone(originDir, cloneDir)

            assertEquals("origin/HEAD", service.remoteDefaultBranchRef(cloneDir, "origin"))
        } finally {
            removeTempDir(originDir)
            removeTempDir(cloneDir)
        }
    }

    @Test
    fun isAncestor_reportsMergeBaseIsAncestorSemantics() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature/base-pr")
            executeGit("-C", repoDir, "checkout", "-b", "feature/stacked-pr", "feature/base-pr")
            writeFixtureFile(repoDir, "child.txt", "child\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "child")

            assertTrue(service.isAncestor(repoDir, "feature/base-pr", "feature/stacked-pr"))
            assertFalse(service.isAncestor(repoDir, "feature/stacked-pr", "feature/base-pr"))
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun hasCommitsNotContainedIn_reportsWhetherSourceHasCommitsOutsideContainingRef() {
        val repoDir = createTempDir("repo")
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature/base-pr")
            executeGit("-C", repoDir, "checkout", "-b", "feature/stacked-pr", "feature/base-pr")
            writeFixtureFile(repoDir, "child.txt", "child\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "child")

            assertFalse(service.hasCommitsNotContainedIn(repoDir, "feature/base-pr", "feature/stacked-pr"))

            executeGit("-C", repoDir, "checkout", "feature/base-pr")
            writeFixtureFile(repoDir, "parent.txt", "parent\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "parent")

            assertTrue(service.hasCommitsNotContainedIn(repoDir, "feature/base-pr", "feature/stacked-pr"))
        } finally {
            removeTempDir(repoDir)
        }
    }

    @Test
    fun rebase_rebasesCurrentWorktreeOntoUpstreamRefWithAutostash() {
        val repoDir = createTempDir("repo")
        val worktreeDir = createTempDir("wt")
        removeTempDir(worktreeDir)
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature/base-pr")
            executeGit("-C", repoDir, "checkout", "-b", "feature/stacked-pr", "feature/base-pr")
            writeFixtureFile(repoDir, "child.txt", "child\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "child")
            executeGit("-C", repoDir, "checkout", "feature/base-pr")
            writeFixtureFile(repoDir, "parent.txt", "parent\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "parent")
            service.worktreeAdd(repoDir, worktreeDir, "feature/stacked-pr")
            writeFixtureFile(worktreeDir, "child.txt", "child dirty\n")

            service.rebase(worktreeDir, "feature/base-pr")

            assertTrue(service.isAncestor(worktreeDir, "feature/base-pr", "feature/stacked-pr"))
            assertTrue(service.status(worktreeDir).contains("child.txt"))
        } finally {
            removeTempDir(repoDir)
            removeTempDir(worktreeDir)
        }
    }

    @Test
    fun abortRebase_abortsRebaseInCurrentWorktree() {
        val repoDir = createTempDir("repo")
        val worktreeDir = createTempDir("wt")
        removeTempDir(worktreeDir)
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature/base-pr")
            executeGit("-C", repoDir, "checkout", "-b", "feature/stacked-pr", "feature/base-pr")
            writeFixtureFile(repoDir, "README.md", "child\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "child")
            executeGit("-C", repoDir, "checkout", "feature/base-pr")
            writeFixtureFile(repoDir, "README.md", "parent\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "parent")
            service.worktreeAdd(repoDir, worktreeDir, "feature/stacked-pr")

            assertFailsWith<GitCommandException> {
                service.rebase(worktreeDir, "feature/base-pr")
            }
            service.abortRebase(worktreeDir)

            assertEquals("", service.status(worktreeDir))
        } finally {
            removeTempDir(repoDir)
            removeTempDir(worktreeDir)
        }
    }

    @Test
    fun worktree_addListRemove_lifecycle() {
        val repoDir = createTempDir("repo")
        val worktreeDir = createTempDir("wt")
        removeTempDir(worktreeDir)
        try {
            initRepoWithCommit(repoDir)
            executeGit("-C", repoDir, "branch", "feature-branch")

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
    fun worktreeAddNewBranch_createsBranchFromCommitIshBase() {
        val repoDir = createTempDir("repo")
        val worktreeDir = createTempDir("wt")
        removeTempDir(worktreeDir)
        try {
            val baseCommit = initRepoWithCommit(repoDir)
            writeFixtureFile(repoDir, "mainline.txt", "mainline\n")
            executeGit("-C", repoDir, "add", ".")
            executeGit("-C", repoDir, "commit", "-m", "mainline")
            val currentCommit = executeGit("-C", repoDir, "rev-parse", "HEAD").trim()
            assertFalse(baseCommit == currentCommit, "Test setup requires HEAD to differ from the base commit-ish")

            service.worktreeAddNewBranch(
                repoDir,
                "feature/stacked-pr",
                worktreeDir,
                baseCommit,
            )

            val branch = executeGit("-C", worktreeDir, "branch", "--show-current").trim()
            assertEquals("feature/stacked-pr", branch)
            val branchStart = executeGit("-C", worktreeDir, "rev-parse", "HEAD").trim()
            assertEquals(baseCommit, branchStart)

            val listing = service.worktreeList(repoDir)
            assertTrue(listing.contains(worktreeDir), "worktreeList should contain the new worktree path")
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
    fun isValidBranchRefFormat_usesGitCheckRefFormatBranchSemantics() {
        assertTrue(service.isValidBranchRefFormat("feature/stacked-pr"))
        assertFalse(service.isValidBranchRefFormat("feature//stacked-pr"))
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
