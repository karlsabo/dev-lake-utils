package com.github.karlsabo.devlake.enghub.agent

/**
 * Changed paths one review may carry. The prompt lists every one, so a larger change set fails before the
 * reviewer opens rather than dropping paths.
 */
internal const val MAX_PROMPT_CHANGED_PATHS = 500

private const val NO_WORKING_TREE_CHANGES = "(no working-tree changes)"
private const val NO_TRACKED_DIFF =
    "(no tracked content diff; inspect the paths above with the read tool)"
private const val TRUNCATED_PATCH_NOTICE =
    "[Diff truncated to fit this prompt; inspect the surviving changed files with the read tool. " +
        "Removed content of deleted files is kept in full, because a deleted file cannot be read.]"

/** The user prompt that starts the implementor stage for [story]. */
internal fun implementorTaskPrompt(story: String): String = """
    Story:
    ${story.trim()}

    Implement this story in the current working tree.
""".trimIndent()

/**
 * The user prompt that starts the reviewer stage.
 *
 * It carries the story, the implementor's unverified claims, and the changes read from the working tree after
 * the implementor closed; the verified changes, not the claim, are what the reviewer judges.
 */
internal fun reviewerTaskPrompt(
    story: String,
    implementation: ImplementationReport,
    changes: WorkingTreeChangeReport,
): String = buildString {
    appendLine("Story:")
    appendLine(story.trim())
    appendLine()
    appendLine("The implementor reported, as an unverified claim:")
    appendLine("files: ${implementation.files.joinToString(", ").ifEmpty { "(none)" }}")
    appendLine("what: ${implementation.what}")
    appendLine("why: ${implementation.why}")
    appendLine()
    append(verifiedChangesSection(changes))
    appendLine()
    append("Review this implementation independently. Read the contents of every added (A) file ")
    append("listed above before giving a pass verdict; untracked files have no diff. Use the read tool ")
    append("to inspect other files as needed, and judge the verified changes and the files themselves.")
}

/**
 * Renders the verified change report for the reviewer prompt.
 *
 * Every changed path is listed; overflow never gets here because [WorkingTreeChanges.read] fails first. When
 * the diff is cut, the notice tells the reviewer to read the surviving files.
 */
internal fun verifiedChangesSection(changes: WorkingTreeChangeReport): String = buildString {
    appendLine("Verified working-tree changes (git, baseline ${changes.baselineRevision}, working-directory paths):")
    if (changes.changes.isEmpty()) {
        appendLine(NO_WORKING_TREE_CHANGES)
    } else {
        changes.changes.forEach { change -> appendLine("${change.kind.label} ${change.path}") }
        appendLine()
        appendLine("Unified diff of tracked changes:")
        appendLine(changes.patch.ifEmpty { NO_TRACKED_DIFF })
        if (changes.patchTruncated) appendLine(TRUNCATED_PATCH_NOTICE)
    }
}
