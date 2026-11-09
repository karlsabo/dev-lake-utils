package com.github.karlsabo.devlake.enghub.agent

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

private const val MAX_TEST_PATCH_CHARS = 400

/**
 * Integration tests for [GitWorkingTreeChanges] against real, disposable git repositories.
 *
 * They cover the reviewer's core guarantee: the change set comes from the working tree, so an edit the
 * implementor never reported and a file it deleted are both visible.
 */
class GitWorkingTreeChangesTest {
    @Test
    fun `reports changed, added, and deleted paths the implementor did not report`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.write("Removed.kt", "fun removed() = \"gone\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("Greeting.kt", "fun greet() = \"Hello, Ada!\"\n")
            repo.delete("Removed.kt")
            repo.write("new-dir/Added.kt", "fun added() = 1\n")

            val report = changes.read(baseline)

            assertEquals(baseline.revision, report.baselineRevision)
            assertChange(report, "Greeting.kt", WorkingTreeChangeKind.MODIFIED)
            assertChange(report, "Removed.kt", WorkingTreeChangeKind.DELETED)
            assertChange(report, "new-dir/Added.kt", WorkingTreeChangeKind.ADDED)
            assertTrue(report.patch.contains("-fun removed() = \"gone\""), "the deleted content must be visible")
            assertTrue(report.patch.contains("+fun greet() = \"Hello, Ada!\""))
            assertFalse(report.patchTruncated)
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reports changes the implementor committed after the baseline`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("Greeting.kt", "fun greet() = \"Hello, Ada!\"\n")
            repo.commitAll("implementor commit")

            val report = changes.read(baseline)

            assertChange(report, "Greeting.kt", WorkingTreeChangeKind.MODIFIED)
            assertTrue(report.patch.contains("+fun greet() = \"Hello, Ada!\""))
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reports nothing for an unchanged working tree`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)

            val report = changes.read(changes.baseline())

            assertTrue(report.changes.isEmpty(), "a clean tree has no changes, got: ${report.changes}")
            assertTrue(report.patch.isEmpty())
            assertFalse(report.patchTruncated)
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `ignores ignored files so build output does not flood the review`() {
        val repo = TempRepository()
        try {
            repo.write(".gitignore", "build/\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("build/output.txt", "generated\n")

            val report = changes.read(baseline)

            assertTrue(report.changes.isEmpty(), "ignored files must not appear, got: ${report.changes}")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `truncates a diff that is larger than the prompt budget`() {
        val repo = TempRepository()
        try {
            repo.write("Big.kt", (1..MAX_TEST_PATCH_CHARS).joinToString("\n") { index -> "line $index" } + "\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = MAX_TEST_PATCH_CHARS)
            val baseline = changes.baseline()

            repo.write("Big.kt", (1..MAX_TEST_PATCH_CHARS).joinToString("\n") { index -> "changed $index" } + "\n")

            val report = changes.read(baseline)

            assertTrue(report.patchTruncated, "a diff past the budget must be marked truncated")
            assertTrue(report.patch.length <= MAX_TEST_PATCH_CHARS)
            assertTrue(report.patch.endsWith("\n"), "truncation must stop at a line boundary")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `keeps removed content when a large earlier diff pushes a deletion past the budget`() {
        val repo = TempRepository()
        try {
            repo.write("aaa-large.txt", numberedLines(MAX_TEST_PATCH_CHARS, "line"))
            repo.write("zzz-deleted.txt", "fun removed() = \"gone\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = MAX_TEST_PATCH_CHARS)
            val baseline = changes.baseline()

            repo.write("aaa-large.txt", numberedLines(MAX_TEST_PATCH_CHARS, "changed"))
            repo.delete("zzz-deleted.txt")

            val report = changes.read(baseline)

            assertChange(report, "zzz-deleted.txt", WorkingTreeChangeKind.DELETED)
            assertTrue(report.patchTruncated, "the diff is past the budget, so it must be marked truncated")
            assertTrue(
                report.patch.contains("-fun removed() = \"gone\""),
                "a deletion after the truncation point must stay inspectable",
            )
            assertTrue(report.patch.length <= MAX_TEST_PATCH_CHARS, "the reserved removal must fit the budget")
            assertTrue(report.patch.endsWith("\n"), "truncation must stop at a line boundary")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when a deletion's removed content cannot fit the budget`() {
        val repo = TempRepository()
        try {
            repo.write("Deleted.kt", numberedLines(MAX_TEST_PATCH_CHARS, "removed"))
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = MAX_TEST_PATCH_CHARS)
            val baseline = changes.baseline()

            repo.delete("Deleted.kt")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(
                failure.message.orEmpty().contains("cannot fit the removed content"),
                "an uninspectable deletion must fail closed, got: ${failure.message}",
            )
            assertTrue(failure.message.orEmpty().contains("Deleted.kt"))
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reports paths relative to a working directory inside the repository`() {
        val repo = TempRepository()
        try {
            repo.write("module/Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val moduleDir = Path(repo.path, "module")
            val changes = GitWorkingTreeChanges(moduleDir.toString())
            val baseline = changes.baseline()

            repo.write("module/Greeting.kt", "fun greet() = \"Hello, Ada!\"\n")

            val report = changes.read(baseline)

            assertChange(report, "Greeting.kt", WorkingTreeChangeKind.MODIFIED)
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `refuses a directory that is not a git working tree`() {
        val repository = TempDirectory("eng-hub-not-a-repo-${Random.nextLong()}")
        try {
            repository.createDirectories()
            val changes = GitWorkingTreeChanges(repository.path)

            val failure = assertFailsWith<IllegalStateException> { changes.baseline() }

            assertTrue(failure.message.orEmpty().contains("needs a git working tree with at least one commit"))
        } finally {
            repository.delete()
        }
    }

    private fun assertChange(
        report: WorkingTreeChangeReport,
        path: String,
        kind: WorkingTreeChangeKind,
    ) {
        assertEquals(
            kind,
            report.changes.singleOrNull { change -> change.path == path }?.kind,
            "expected $kind $path in ${report.changes}",
        )
    }
}

/** [count] lines tagged [label], long enough that a diff of them cannot fit a small budget. */
private fun numberedLines(
    count: Int,
    label: String,
): String = (1..count).joinToString("\n") { index -> "$label $index" } + "\n"

/** A disposable git repository rooted below the system temporary directory. */
private class TempRepository : TempDirectory("eng-hub-git-changes-${Random.nextLong()}") {
    init {
        createDirectories()
        git("init", "-q", path)
        git("-C", path, "config", "user.email", "reviewer@example.com")
        git("-C", path, "config", "user.name", "Eng Hub Test")
    }

    fun write(
        relativePath: String,
        content: String,
    ) {
        val file = Path(path, relativePath)
        file.parent?.let { parent -> SystemFileSystem.createDirectories(parent, mustCreate = false) }
        SystemFileSystem.sink(file).buffered().use { sink -> sink.writeString(content) }
    }

    fun delete(relativePath: String) {
        SystemFileSystem.delete(Path(path, relativePath))
    }

    fun commitAll(message: String) {
        git("-C", path, "add", "--all")
        git("-C", path, "commit", "-q", "-m", message)
    }

    private fun git(vararg arguments: String) {
        val command = listOf("git", *arguments)
        val result = executeCommand(command, workingDirectory = null)
        check(result.exitCode == 0) {
            "Fixture git command failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}"
        }
    }
}

/** A disposable directory below the system temporary directory. */
private open class TempDirectory(
    private val name: String,
) {
    val path: String = Path(SystemTemporaryDirectory, name).toString()

    fun createDirectories() {
        SystemFileSystem.createDirectories(Path(path), mustCreate = false)
    }

    fun delete() {
        val root = Path(path)
        if (!SystemFileSystem.exists(root)) return
        deleteRecursively(root)
    }

    private fun deleteRecursively(directory: Path) {
        if (SystemFileSystem.metadataOrNull(directory)?.isDirectory == true) {
            SystemFileSystem.list(directory).forEach { child -> deleteRecursively(child) }
        }
        SystemFileSystem.delete(directory, mustExist = false)
    }
}
