package com.github.karlsabo.devlake.enghub.agent

/** Paths the reviewer prompt lists before it summarizes that more changed paths exist. */
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
 * It carries the story, the implementor's claims, and the changes ordinary code read from the working
 * tree after the implementor session closed. The verified changes are what the reviewer must judge:
 * the implementor's `files` list is only a claim, and a changed or deleted file it omits still appears
 * in the verified list.
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
    append("Review this implementation independently. Use the read tool to inspect the files you need, ")
    append("and judge the verified changes and the files themselves.")
}

/**
 * Renders the verified change report for the reviewer prompt.
 *
 * The path list is where omissions and deletions surface, so it keeps every changed path up to
 * [MAX_PROMPT_CHANGED_PATHS] and says how many it dropped rather than silently losing them. The diff is
 * the content proof; when it is cut, the notice tells the reviewer to read the surviving files instead and
 * that a deleted file's removed content is still in the patch.
 */
internal fun verifiedChangesSection(changes: WorkingTreeChangeReport): String = buildString {
    appendLine("Verified working-tree changes (git, baseline ${changes.baselineRevision}, working-directory paths):")
    if (changes.changes.isEmpty()) {
        appendLine(NO_WORKING_TREE_CHANGES)
    } else {
        changes.changes.take(MAX_PROMPT_CHANGED_PATHS).forEach { change ->
            appendLine("${change.kind.label} ${change.path}")
        }
        val omitted = changes.changes.size - MAX_PROMPT_CHANGED_PATHS
        if (omitted > 0) {
            appendLine("... and $omitted more changed paths not listed here.")
        }
        appendLine()
        appendLine("Unified diff of tracked changes:")
        appendLine(changes.patch.ifEmpty { NO_TRACKED_DIFF })
        if (changes.patchTruncated) appendLine(TRUNCATED_PATCH_NOTICE)
    }
}
