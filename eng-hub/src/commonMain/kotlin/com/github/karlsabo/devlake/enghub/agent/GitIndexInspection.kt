package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.git.GitCommandApi

private const val NUL_SEPARATED = "-z"
private const val END_OF_OPTIONS = "--"
private const val GITLINK_MODE = "160000"

/** Refuses index entries whose working-tree content the reviewer's file reader cannot verify. */
internal class GitIndexInspection(
    private val workingDirectory: String,
    private val git: GitCommandApi,
) {
    /** Check flags before diffing: git silently skips working-tree edits behind either index flag. */
    fun requireVisibleTrackedFiles() {
        val hidden = splitNulPaths(
            git.executeRaw(workingDirectory, listOf("ls-files", "-v", NUL_SEPARATED, END_OF_OPTIONS)),
        ).filter { entry -> entry.firstOrNull()?.let { flag -> flag.isLowerCase() || flag == 'S' } == true }
            .map { entry -> entry.drop(2) }
        check(hidden.isEmpty()) {
            "Eng Hub cannot verify tracked paths marked assume-unchanged or skip-worktree: " +
                hidden.joinToString(", ") + ". Git diff can omit their working-tree edits; clear the index flags " +
                "before review."
        }
    }

    /** A gitlink's diff shows only its commit ID, not dirty files inside the submodule. */
    fun requireInspectableGitlinks(changes: List<WorkingTreeChange>) {
        val changedPaths = changes.map { change -> change.path }.toSet()
        val gitlinks = splitNulPaths(
            git.executeRaw(workingDirectory, listOf("ls-files", "--stage", NUL_SEPARATED, END_OF_OPTIONS)),
        ).mapNotNull { entry ->
            val path = entry.substringAfter('\t', missingDelimiterValue = "")
            path.takeIf { entry.startsWith("$GITLINK_MODE ") && it in changedPaths }
        }
        check(gitlinks.isEmpty()) {
            "Eng Hub cannot inspect changed tracked submodule(s): ${gitlinks.joinToString(", ")}. " +
                "Git diff shows only the gitlink commit, not edits inside the submodule, and the reviewer " +
                "cannot read a directory. Review the submodule separately."
        }
    }
}
