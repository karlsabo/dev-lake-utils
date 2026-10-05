package com.github.karlsabo.devlake.enghub.viewmodel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitDirectoryForceArchiveProvenanceTest {
    @Test
    fun originalCheckoutRetainsAuthorizationAcrossRestartButReplacementDoesNot() {
        val root = Files.createTempDirectory("force-provenance")
        try {
            val checkout = Files.createDirectory(root.resolve("checkout"))
            val admin = Files.createDirectory(root.resolve("admin"))
            val gitFile = checkout.resolve(".git")
            Files.writeString(gitFile, "gitdir: $admin\n")
            Files.writeString(admin.resolve("gitdir"), "$gitFile\n")
            val job = restartQueuedJob().copy(worktreePath = checkout.toString(), queueId = "original")
            GitDirectoryForceArchiveProvenance.record(job)
            assertTrue(GitDirectoryForceArchiveProvenance.matches(job))
            assertFalse(GitDirectoryForceArchiveProvenance.matches(job.copy(queueId = "other")))

            Files.delete(admin.resolve("eng-hub-force-archive-provenance"))
            Files.writeString(gitFile, "gitdir: $admin\n")
            assertFalse(GitDirectoryForceArchiveProvenance.matches(job))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
