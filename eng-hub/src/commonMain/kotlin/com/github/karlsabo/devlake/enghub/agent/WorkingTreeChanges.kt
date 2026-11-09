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
    /**
     * The unified diff; when [patchTruncated], the removed content of every deleted path is still complete.
     *
     * A deleted path whose content git can only report as binary never reaches this report: [read] fails
     * instead, because the reviewer could neither see the removed bytes nor reopen the deleted path.
     */
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

    /**
     * Reads every working-tree change relative to [baseline], including deletions and untracked paths.
     *
     * A reviewer can re-read a surviving file but never a deleted one, so a [WorkingTreeChangeReport] whose diff
     * dropped a deletion's removed content would let the reviewer pass on a change it could not inspect.
     * Implementations keep that content within their prompt budget, or fail instead of returning the report.
     * They do the same when git can only describe a deleted path as binary, because a marker is not the removed
     * content and the deleted path cannot be reopened. A working tree with more changed paths than the prompt
     * lists also fails, because a path the prompt omits is one the reviewer cannot discover or read.
     */
    fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport
}

/**
 * Explains a refusal to review a working tree with more changed paths than the prompt can list.
 *
 * Untracked paths have no diff content, and the reviewer reads only the paths the prompt names, so a path the
 * prompt drops cannot be rediscovered. Truncating the list would let the reviewer pass on changes it never saw.
 *
 * @param count the number of changed paths the working tree reported.
 * @param limit the most changed paths the reviewer prompt can carry.
 */
internal fun tooManyChangedPaths(
    count: Int,
    limit: Int,
): String = "Eng Hub found $count changed paths, more than the $limit the reviewer prompt can list. " +
    "An untracked path has no diff content and the reviewer only reads the paths the prompt names, so a path " +
    "left out of the list could be neither discovered nor read. Failing the review instead of listing only " +
    "part of the change set."

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
