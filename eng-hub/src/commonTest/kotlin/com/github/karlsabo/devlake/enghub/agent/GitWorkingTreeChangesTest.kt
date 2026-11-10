package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.devlake.enghub.isSymbolicLink
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

/** Bytes git treats as binary, so its diff carries a marker instead of the content. */
private const val BINARY_PAYLOAD = "payload\u0000\u0001\u0002"
private const val OTHER_BINARY_PAYLOAD = "other-payload\u0000\u0003"

/** Integration tests for [GitWorkingTreeChanges] against real, disposable git repositories. */
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
    fun `refuses a hidden edit marked assume-unchanged`() {
        val repo = TempRepository()
        try {
            repo.write("hidden.txt", "old\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()
            repo.git("-C", repo.path, "update-index", "--assume-unchanged", "hidden.txt")
            repo.write("hidden.txt", "new\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(failure.message.orEmpty().contains("hidden.txt"))
            assertTrue(failure.message.orEmpty().contains("assume-unchanged"))
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `refuses a hidden edit marked skip-worktree`() {
        val repo = TempRepository()
        try {
            repo.write("hidden.txt", "old\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()
            repo.git("-C", repo.path, "update-index", "--skip-worktree", "hidden.txt")
            repo.write("hidden.txt", "new\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(failure.message.orEmpty().contains("hidden.txt"))
            assertTrue(failure.message.orEmpty().contains("skip-worktree"))
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `refuses edits inside a tracked submodule`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "hello\n")
            val nested = Path(repo.path, "nested").toString()
            repo.git("init", "-q", nested)
            repo.git("-C", nested, "config", "user.email", "reviewer@example.com")
            repo.git("-C", nested, "config", "user.name", "Eng Hub Test")
            repo.write("nested/hidden.txt", "old\n")
            repo.git("-C", nested, "add", "hidden.txt")
            repo.git("-C", nested, "commit", "-q", "-m", "nested baseline")
            repo.commitAll("baseline with gitlink")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()
            repo.write("nested/hidden.txt", "new\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(failure.message.orEmpty().contains("nested"))
            assertTrue(failure.message.orEmpty().contains("submodule"))
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

    @Test
    fun `fails the review when a deleted binary file's removed content is not inspectable`() {
        val repo = TempRepository()
        try {
            repo.write("payload.bin", BINARY_PAYLOAD)
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.delete("payload.bin")

            assertUninspectableBinaryChange(changes, baseline, "payload.bin")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review for a deleted binary file even when the diff is split for truncation`() {
        val repo = TempRepository()
        try {
            repo.write("aaa-large.txt", numberedLines(MAX_TEST_PATCH_CHARS, "line"))
            repo.write("payload.bin", BINARY_PAYLOAD)
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = MAX_TEST_PATCH_CHARS)
            val baseline = changes.baseline()

            repo.write("aaa-large.txt", numberedLines(MAX_TEST_PATCH_CHARS, "changed"))
            repo.delete("payload.bin")

            assertUninspectableBinaryChange(changes, baseline, "payload.bin")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when a tracked binary file's content changes`() {
        val repo = TempRepository()
        try {
            repo.write("payload.bin", BINARY_PAYLOAD)
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("payload.bin", OTHER_BINARY_PAYLOAD)
            repo.write("Greeting.kt", "fun greet() = \"Hello, Ada!\"\n")

            val message = assertUninspectableBinaryChange(changes, baseline, "payload.bin")
            assertFalse(
                message.contains("Greeting.kt"),
                "a readable text change must not be named as uninspectable, got: $message",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when the implementor stages an added binary file`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("payload.bin", BINARY_PAYLOAD)
            repo.stage("payload.bin")

            assertUninspectableBinaryChange(changes, baseline, "payload.bin")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when the implementor adds an untracked binary file`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("payload.bin", BINARY_PAYLOAD)

            assertUninspectableBinaryChange(changes, baseline, "payload.bin")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review for a binary change inside a working directory that is a subdirectory`() {
        val repo = TempRepository()
        try {
            repo.write("module/payload.bin", BINARY_PAYLOAD)
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(Path(repo.path, "module").toString())
            val baseline = changes.baseline()

            repo.write("module/payload.bin", OTHER_BINARY_PAYLOAD)

            assertUninspectableBinaryChange(changes, baseline, "payload.bin")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reviews a binary file whose mode changed but whose content did not`() {
        val repo = TempRepository()
        try {
            repo.write("payload.bin", BINARY_PAYLOAD)
            repo.commitAll("baseline")
            repo.config("core.filemode", "false")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.markExecutable("payload.bin")

            val report = changes.read(baseline)

            assertChange(report, "payload.bin", WorkingTreeChangeKind.MODIFIED)
            assertTrue(
                report.patch.contains("new mode 100755"),
                "a mode-only change carries its mode lines, so it stays reviewable, got: ${report.patch}",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when the change set has more paths than the prompt can list`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxChangedPaths = 1)
            val baseline = changes.baseline()

            repo.write("One.kt", "1\n")
            repo.write("Two.kt", "2\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            val message = failure.message.orEmpty()
            assertTrue(
                message.contains("found 2 changed paths"),
                "the failure must name how many paths it found, got: $message",
            )
            assertTrue(message.contains("more than the 1"), "the failure must name the prompt limit, got: $message")
            assertTrue(
                message.contains("could be neither discovered nor read"),
                "the failure must explain why an omitted untracked path is unreachable, got: $message",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reviews a change set that exactly fills the path limit`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxChangedPaths = 2)
            val baseline = changes.baseline()

            repo.write("One.kt", "1\n")
            repo.write("Two.kt", "2\n")

            val report = changes.read(baseline)

            assertEquals(2, report.changes.size, "a change set at the limit must still be reviewed")
            assertChange(report, "Two.kt", WorkingTreeChangeKind.ADDED)
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review instead of running a configured clean filter that hides a changed line`() {
        val repo = TempRepository()
        try {
            repo.write("notes.txt", "alpha\n")
            repo.write(".gitattributes", "*.txt filter=strip\n")
            repo.commitAll("baseline")
            val marker = Path(repo.path, "filter-ran.txt").toString()
            repo.config("filter.strip.clean", "sed '/hidden/d'; touch '$marker'")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("notes.txt", "alpha\nhidden\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            val message = failure.message.orEmpty()
            assertTrue(
                message.contains("filter.strip.clean"),
                "the failure must name the configured filter command, got: $message",
            )
            assertTrue(
                message.contains("clean filter"),
                "the failure must explain how a clean filter hides a change, got: $message",
            )
            assertFalse(
                SystemFileSystem.exists(Path(marker)),
                "a repository-configured clean filter must never run while the review reads changes",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review instead of running a content-preserving configured clean filter`() {
        val repo = TempRepository()
        try {
            repo.write("notes.txt", "alpha\n")
            repo.write(".gitattributes", "*.txt filter=touch-marker\n")
            repo.commitAll("baseline")
            val marker = Path(repo.path, "filter-ran.txt").toString()
            repo.config("filter.touch-marker.clean", "cat; touch '$marker'")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("notes.txt", "alpha\nbeta\n")

            assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertFalse(
                SystemFileSystem.exists(Path(marker)),
                "a filter command is outside the reviewer's read-only boundary even when it keeps content",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review instead of running a configured clean filter on a path that starts with a space`() {
        val repo = TempRepository()
        try {
            repo.write(" leading.txt", "alpha\n")
            repo.write("leading.txt", "unfiltered\n")
            repo.write(".gitattributes", "\" leading.txt\" filter=trap\n")
            repo.commitAll("baseline")
            val marker = Path(repo.path, "filter-ran.txt").toString()
            repo.config("filter.trap.clean", "cat; touch '$marker'")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write(" leading.txt", "alpha\nbeta\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(
                failure.message.orEmpty().contains("filter.trap.clean"),
                "the filter of the only path it matches must be found, got: ${failure.message}",
            )
            assertFalse(
                SystemFileSystem.exists(Path(marker)),
                "a filter named by a leading-space path must never run, because trimming would miss it",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `lists an untracked path that starts with a space without trimming it`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write(" leading-new.txt", "new content\n")

            val report = changes.read(baseline)

            assertChange(report, " leading-new.txt", WorkingTreeChangeKind.ADDED)
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review instead of running the filter of a path that follows an empty filter value`() {
        val repo = TempRepository()
        try {
            repo.write("empty.txt", "alpha\n")
            repo.write("trap.txt", "alpha\n")
            // An empty filter value keeps a path with no filter, but git still writes an empty value field for it
            // in `check-attr -z`. Dropping that field shifts the triples and hides the next path's driver.
            repo.write(".gitattributes", "empty.txt filter=\ntrap.txt filter=evil\n")
            repo.commitAll("baseline")
            val marker = Path(repo.path, "filter-ran.txt").toString()
            repo.config("filter.evil.clean", "cat; touch '$marker'")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("trap.txt", "alpha\nbeta\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(
                failure.message.orEmpty().contains("filter.evil.clean"),
                "the driver after an empty filter value must be found, got: ${failure.message}",
            )
            assertFalse(
                SystemFileSystem.exists(Path(marker)),
                "a shifted triple must never let a configured clean filter run while the review reads changes",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when an untracked symlink hides the target it points at`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.symlink("missing-link.txt", "missing.txt")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            val message = failure.message.orEmpty()
            assertTrue(
                message.contains("missing-link.txt"),
                "the failure must name the untracked symlink, got: $message",
            )
            assertTrue(
                message.contains("symlink"),
                "the failure must explain that the target of a symlink is what it cannot show, got: $message",
            )
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `reviews a working tree whose filter attribute has no configured command`() {
        val repo = TempRepository()
        try {
            repo.write("notes.txt", "alpha\n")
            repo.write(".gitattributes", "*.txt filter=inert\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            repo.write("notes.txt", "alpha\nbeta\n")

            val report = changes.read(baseline)

            assertChange(report, "notes.txt", WorkingTreeChangeKind.MODIFIED)
            assertTrue(report.patch.contains("+beta"), "an inert filter must not block the review")
        } finally {
            repo.delete()
        }
    }

    @Test
    fun `fails the review when an untracked nested repository hides the files inside it`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()
            val nested = Path(repo.path, "nested").toString()
            repo.git("init", "-q", nested)
            repo.write("nested/hidden.txt", "hidden change\n")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            val message = failure.message.orEmpty()
            assertTrue(
                message.contains("nested/"),
                "the failure must name the opaque directory git reported, got: $message",
            )
            assertTrue(
                message.contains("nested repository"),
                "the failure must explain that a nested repository is what hides the files, got: $message",
            )
            assertFalse(
                message.contains("hidden.txt"),
                "the opaque entry must fail closed instead of pretending hidden.txt was listed, got: $message",
            )
        } finally {
            repo.delete()
        }
    }

    private fun assertUninspectableBinaryChange(
        changes: GitWorkingTreeChanges,
        baseline: WorkingTreeBaseline,
        path: String,
    ): String {
        val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

        val message = failure.message.orEmpty()
        assertTrue(
            message.contains("cannot show the content of 1 changed binary path(s)"),
            "an uninspectable binary change must fail closed, got: $message",
        )
        assertTrue(message.contains(path), "the failure must name $path, got: $message")
        return message
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
internal class TempRepository : TempDirectory("eng-hub-git-changes-${Random.nextLong()}") {
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

    fun symlink(
        relativePath: String,
        target: String,
    ) {
        val command = listOf("ln", "-s", target, Path(path, relativePath).toString())
        val result = executeCommand(command, workingDirectory = null)
        check(result.exitCode == 0) {
            "Fixture symlink command failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}"
        }
    }

    fun commitAll(message: String) {
        git("-C", path, "add", "--all")
        git("-C", path, "commit", "-q", "-m", message)
    }

    fun stage(relativePath: String) {
        git("-C", path, "add", "--", relativePath)
    }

    fun config(
        key: String,
        value: String,
    ) {
        git("-C", path, "config", key, value)
    }

    fun markExecutable(relativePath: String) {
        git("-C", path, "update-index", "--chmod=+x", relativePath)
    }

    fun git(vararg arguments: String): String {
        val command = listOf("git", *arguments)
        val result = executeCommand(command, workingDirectory = null)
        check(result.exitCode == 0) {
            "Fixture git command failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}"
        }
        return result.stdout
    }
}

/** A disposable directory below the system temporary directory. */
internal open class TempDirectory(
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
        if (directory.isSymbolicLink()) {
            deleteSymbolicLink(directory)
            return
        }
        if (SystemFileSystem.metadataOrNull(directory)?.isDirectory == true) {
            SystemFileSystem.list(directory).forEach { child -> deleteRecursively(child) }
        }
        SystemFileSystem.delete(directory, mustExist = false)
    }

    /**
     * Removes a symlink itself, including a dangling one.
     *
     * `SystemFileSystem.delete` follows the link when checking existence, so a dangling symlink is left behind
     * and its parent directory cannot be removed.
     */
    private fun deleteSymbolicLink(symlink: Path) {
        val command = listOf("rm", "-f", symlink.toString())
        val result = executeCommand(command, workingDirectory = null)
        check(result.exitCode == 0) {
            "Fixture symlink removal failed (${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}"
        }
    }
}
