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
    "[Diff truncated to fit this prompt; read every added file. " +
        "Diffs of modified and deleted files are kept in full because their removed lines cannot be read.]"

/** The report contract the implementor stage must satisfy. */
internal const val IMPLEMENTOR_REPORT_CONTRACT = """{"files":["relative/path.kt"],"what":"what changed","why":"why it changed"}
files lists every file you touched and may be empty when the story needs no edit.
what and why must be nonblank.
"""

/** The report contract the reviewer stage must satisfy. */
internal const val REVIEWER_REPORT_CONTRACT = """{"verdict":"pass","findings":[]}
verdict is "pass" or "needs_changes". findings lists concrete problems, one per entry.
A pass has no findings; needs_changes has at least one finding.
"""

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
 * The follow-up prompt sent in a stage's existing conversation after its report fails validation.
 *
 * The prompt is self-contained: it carries the stage's original task prompt and the rejected reply, so the
 * repair does not depend on those messages surviving the session's bounded history. The repair still runs in
 * the same session, so the tool exchange is present when the history cap keeps it.
 */
internal fun reportRepairPrompt(
    stage: StageName,
    taskPrompt: String,
    rejectedReply: String,
    rejectionReason: String,
): String = buildString {
    appendLine("The ${stage.id} stage's original request was:")
    appendLine(taskPrompt.trim())
    appendLine()
    appendLine("Your previous reply could not be accepted as the ${stage.id} report.")
    appendLine("Rejected reply:")
    appendLine(rejectedReply.trim())
    appendLine()
    appendLine("Rejection reason: $rejectionReason")
    appendLine()
    appendLine("Reply again with only one JSON object, using this contract:")
    append(stageReportContract(stage))
    append("Do not repeat completed tool work; correct the report only.")
}

internal fun stageReportContract(stage: StageName): String = when (stage) {
    StageName.IMPLEMENTOR -> IMPLEMENTOR_REPORT_CONTRACT
    StageName.REVIEWER -> REVIEWER_REPORT_CONTRACT
}

/**
 * Renders the verified change report for the reviewer prompt.
 *
 * Every changed path is listed; overflow never gets here because [WorkingTreeChanges.read] fails first. When
 * the diff is cut, the notice tells the reviewer to read added files.
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
