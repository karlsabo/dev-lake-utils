package com.github.karlsabo.git

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorktreeServiceArchiveTest {
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
        val worktreePath = createArchiveWorktreeTempDir()
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
        val worktreePath = createArchiveWorktreeTempDir()
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
        val worktreePath = createArchiveWorktreeTempDir()
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
