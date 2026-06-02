package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitWorktreeServiceParseTest {
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
}
