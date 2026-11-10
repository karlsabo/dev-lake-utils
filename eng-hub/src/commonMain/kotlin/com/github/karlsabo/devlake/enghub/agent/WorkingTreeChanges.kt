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
     * The unified diff. When [patchTruncated], every deleted path's removed content is still complete.
     *
     * Binary changes never reach this report: [read] fails instead, because the reviewer's text-only `read`
     * tool cannot show their bytes.
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
     * Fails closed on any change the reviewer could not inspect: a deletion whose removed content does not fit
     * the prompt budget, binary paths (git shows a marker, or no diff for untracked; `read` is text-only),
     * opaque untracked directories and untracked symlinks, more changed paths than the prompt lists, configured
     * clean/process filters (which run repository code and can hide a line), hidden index flags, and changed
     * tracked submodules.
     */
    fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport
}

/**
 * Explains a refusal to review a working tree with more changed paths than the prompt can list.
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

/**
 * Explains a refusal to review binary changes no reviewer tool can inspect.
 *
 * @param changes the changed binary paths whose content no reviewer tool can show.
 */
internal fun uninspectableBinaryChanges(changes: List<WorkingTreeChange>): String {
    val listed = changes.joinToString(", ") { change -> "${change.kind.label} ${change.path}" }
    return "Eng Hub cannot show the content of ${changes.size} changed binary path(s): $listed. Git reports " +
        "binary content as a marker instead of the bytes, an untracked binary path has no diff at all, and the " +
        "reviewer's read tool is a text tool, so neither side of the change could be inspected. Failing the " +
        "review instead."
}

/**
 * Explains a refusal to review untracked symlinks whose target no reviewer tool can show.
 *
 * @param paths the untracked symlinks whose target the reviewer cannot inspect.
 */
internal fun uninspectableUntrackedSymlinks(paths: List<String>): String {
    val listed = paths.joinToString(", ")
    return "Eng Hub found ${paths.size} untracked symlink(s): $listed. Git lists an untracked symlink as an " +
        "added path but carries no diff for it, and the reviewer's read tool follows the link instead of " +
        "showing the path it points at, so a dangling link cannot be read at all. Failing the review instead " +
        "of reviewing a change whose target was never seen."
}

/**
 * Explains a refusal to review untracked directory entries git reports as one opaque path.
 *
 * @param paths the untracked directory entries whose contents git did not list.
 */
internal fun opaqueUntrackedDirectories(paths: List<String>): String {
    val listed = paths.joinToString(", ")
    return "Eng Hub found ${paths.size} untracked path(s) git reports as an opaque directory instead of the " +
        "files inside it: $listed. Git lists an untracked nested repository this way, so a file inside it " +
        "appears in no diff and cannot be read by path, and the directory entry is not a file the reviewer can " +
        "read. Failing the review instead of reviewing a change set whose contents are invisible."
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
