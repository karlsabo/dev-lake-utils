package com.github.karlsabo.devlake.enghub.agent

/** The kind of change a working-tree path has relative to the reviewed baseline. */
enum class WorkingTreeChangeKind(
    /** The single-letter code the reviewer sees next to the path, as in `git status`. */
    val label: String,
) {
    ADDED("A"),
    MODIFIED("M"),
    DELETED("D"),
}

/** One changed path, relative to the reviewed working directory. */
data class WorkingTreeChange(
    val path: String,
    val kind: WorkingTreeChangeKind,
)

/**
 * The commit the implementor started from.
 *
 * Changes are read against this revision rather than against the current `HEAD`, because the
 * implementor can commit through its `bash` tool and such commits must stay visible to the reviewer.
 */
data class WorkingTreeBaseline(
    val revision: String,
)

/**
 * What the working tree actually contains relative to a [WorkingTreeBaseline], including deletions and
 * untracked paths.
 *
 * Ordinary code produces this from the working tree itself. The implementor's report never contributes,
 * so a file the implementor omitted from its report still appears here.
 */
data class WorkingTreeChangeReport(
    val baselineRevision: String,
    val changes: List<WorkingTreeChange>,
    val patch: String,
    val patchTruncated: Boolean,
)

/**
 * Read-only change discovery for the reviewer stage.
 *
 * [baseline] must run before the implementor edits anything and [read] after the implementor session
 * closes. Implementations only inspect the working tree; they never change files, the index, or refs.
 */
interface WorkingTreeChanges {
    /** Records the revision the implementor starts from. */
    fun baseline(): WorkingTreeBaseline

    /** Reads every working-tree change relative to [baseline], including deletions and untracked paths. */
    fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport
}

/** Text cut at a line boundary, plus whether anything was dropped. */
internal data class BoundedText(
    val text: String,
    val truncated: Boolean,
)

/** Cuts [text] at the last line boundary inside [maxChars] so the result never ends mid-line. */
internal fun truncateAtLineBoundary(
    text: String,
    maxChars: Int,
): BoundedText {
    require(maxChars > 0) { "maxChars must be positive" }
    if (text.length <= maxChars) return BoundedText(text, truncated = false)
    val boundary = text.lastIndexOf('\n', startIndex = maxChars - 1)
    return if (boundary >= 0) {
        BoundedText(text.substring(0, boundary + 1), truncated = true)
    } else {
        BoundedText("", truncated = true)
    }
}
