package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitIndexInspectionTest {
    @Test
    fun `refuses a staged deletion of a baseline gitlink`() {
        val repo = TempRepository()
        try {
            repo.write("Greeting.kt", "hello\n")
            repo.commitAll("initial commit")
            val commit = repo.git("-C", repo.path, "rev-parse", "HEAD").trim()
            repo.git("-C", repo.path, "update-index", "--add", "--cacheinfo", "160000,$commit,nested")
            repo.git("-C", repo.path, "commit", "-q", "-m", "baseline with gitlink")
            val changes = GitWorkingTreeChanges(repo.path)
            val baseline = changes.baseline()
            repo.git("-C", repo.path, "update-index", "--force-remove", "nested")

            val failure = assertFailsWith<IllegalStateException> { changes.read(baseline) }

            assertTrue(failure.message.orEmpty().contains("nested"))
            assertTrue(failure.message.orEmpty().contains("submodule"))
        } finally {
            repo.delete()
        }
    }
}
