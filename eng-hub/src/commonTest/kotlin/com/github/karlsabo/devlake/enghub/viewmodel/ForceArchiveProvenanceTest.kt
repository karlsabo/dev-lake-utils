package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForceArchiveProvenanceTest {
    @Test
    fun relativeGitPointerAndBacklinkAuthorizeOnlyOriginalCheckout() {
        val root = Path(SystemTemporaryDirectory, "force-provenance-${Random.nextLong()}")
        val checkout = Path(root, "login")
        val admin = Path(root, "repo", ".git", "worktrees", "login")
        val gitFile = Path(checkout, ".git")
        val backlink = Path(admin, "gitdir")
        val job = WorktreeArchiveJob(
            repositoryRootPath = Path(root, "repo").toString(),
            worktreePath = checkout.toString(),
            branch = "feature/login",
            queueId = "original-queue",
            state = WorktreeArchiveLifecycleState.NEEDS_FORCE_CONFIRMATION,
            queuedAtEpochMs = 1,
            stateUpdatedAtEpochMs = 2,
            deadlineAtEpochMs = 3,
        )
        try {
            SystemFileSystem.createDirectories(checkout)
            SystemFileSystem.createDirectories(admin)
            write(gitFile, "gitdir: ../repo/.git/worktrees/login\n")
            write(backlink, "../../../../login/.git\n")
            GitDirectoryForceArchiveProvenance.record(job)
            assertTrue(GitDirectoryForceArchiveProvenance.matches(job))
            assertFalse(GitDirectoryForceArchiveProvenance.matches(job.copy(queueId = "replacement-queue")))
            write(backlink, "../../../../other/.git\n")
            assertFalse(GitDirectoryForceArchiveProvenance.matches(job))
        } finally {
            deleteRecursively(root)
        }
    }

    private fun write(path: Path, content: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
