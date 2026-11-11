package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SYMLINK_TEST_BUDGET = 400

class GitSymlinkReviewTest {
    @Test
    fun `fails when truncation would hide a tracked symlink target`() {
        val repo = TempRepository()
        try {
            repo.write("aaa-large.txt", lines("line"))
            repo.symlink("zzz-link.txt", "original.txt")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path, maxPatchChars = SYMLINK_TEST_BUDGET)
            val baseline = changes.baseline()

            repo.write("aaa-large.txt", lines("changed"))
            repo.delete("zzz-link.txt")
            repo.symlink("zzz-link.txt", "missing.txt")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(failure.message.orEmpty().contains("zzz-link.txt"))
            assertTrue(failure.message.orEmpty().contains("symlink target diff"))
        } finally {
            repo.delete()
        }
    }

    private fun lines(label: String): String {
        val content = (1..SYMLINK_TEST_BUDGET).joinToString("\n") { index -> "$label $index" }
        return "$content\n"
    }
}
