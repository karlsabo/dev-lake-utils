package com.github.karlsabo.devlake.enghub.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitWorkingTreeSymlinkTest {
    @Test
    fun `fails the review when an untracked symlink hides the target it points at`() {
        val repo = TempRepository()
        val link = Path.of(repo.path, "missing-link.txt")
        try {
            repo.write("Greeting.kt", "fun greet() = \"Hello\"\n")
            repo.commitAll("baseline")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()

            Files.createSymbolicLink(link, Path.of("missing.txt"))

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }
            val message = failure.message.orEmpty()
            assertTrue(message.contains("missing-link.txt"), "the failure must name the untracked symlink")
            assertTrue(message.contains("symlink"), "the failure must explain why the target cannot be inspected")
        } finally {
            Files.deleteIfExists(link)
            repo.delete()
        }
    }
}
