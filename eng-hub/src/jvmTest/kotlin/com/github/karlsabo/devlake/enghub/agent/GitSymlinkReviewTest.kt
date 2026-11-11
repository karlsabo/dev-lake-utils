package com.github.karlsabo.devlake.enghub.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SYMLINK_TEST_BUDGET = 400

class GitSymlinkReviewTest {
    @Test
    fun `fails when truncation would hide a tracked symlink target`() {
        val repo = TempRepository()
        val link = Path.of(repo.path, "zzz-link.txt")
        try {
            repo.write("aaa-large.txt", lines("line"))
            Files.createSymbolicLink(link, Path.of("original.txt"))
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = SYMLINK_TEST_BUDGET)
            val baseline = changes.baseline()

            repo.write("aaa-large.txt", lines("changed"))
            Files.delete(link)
            Files.createSymbolicLink(link, Path.of("missing.txt"))

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            val message = failure.message.orEmpty()
            assertTrue(message.contains("zzz-link.txt"), "the failure must name the symlink, got: $message")
            assertTrue(message.contains("symlink target diff"), "the failure must explain why, got: $message")
        } finally {
            Files.deleteIfExists(link)
            repo.delete()
        }
    }

    private fun lines(label: String): String {
        val content = (1..SYMLINK_TEST_BUDGET).joinToString("\n") { index -> "$label $index" }
        return "$content\n"
    }
}
