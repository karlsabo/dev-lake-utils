package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.worktreearchive.WorktreeArchiveJob
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

internal interface ForceArchiveProvenance {
    fun record(job: WorktreeArchiveJob)
    fun matches(job: WorktreeArchiveJob): Boolean
}

internal object GitDirectoryForceArchiveProvenance : ForceArchiveProvenance {
    private const val MARKER = "eng-hub-force-archive-provenance"

    override fun record(job: WorktreeArchiveJob) {
        val marker = requireNotNull(markerPath(job.worktreePath)) {
            "Cannot verify worktree checkout provenance: ${job.worktreePath}"
        }
        SystemFileSystem.sink(marker).buffered().use { it.writeString(job.queueId) }
    }

    override fun matches(job: WorktreeArchiveJob): Boolean {
        val marker = markerPath(job.worktreePath)
        return marker != null && SystemFileSystem.exists(marker) &&
            SystemFileSystem.source(marker).buffered().use { it.readString() } == job.queueId
    }

    private fun markerPath(worktreePath: String): Path? {
        val gitFile = Path(worktreePath, ".git")
        if (SystemFileSystem.metadataOrNull(gitFile)?.isRegularFile != true) return null
        val pointer = SystemFileSystem.source(gitFile).buffered().use { it.readString() }
            .trim().removePrefix("gitdir: ")
        val absolute = pointer.startsWith("/") || pointer.getOrNull(1) == ':'
        val admin = if (absolute) Path(pointer) else Path(worktreePath, pointer)
        // Only Git's linked-worktree admin directory is used; a new worktree add recreates it.
        val backlink = Path(admin, "gitdir")
        val registeredGitFile = if (SystemFileSystem.exists(backlink)) {
            SystemFileSystem.source(backlink).buffered().use { it.readString().trim() }
        } else {
            null
        }
        return if (registeredGitFile != null && Path(registeredGitFile) == gitFile) Path(admin, MARKER) else null
    }
}
