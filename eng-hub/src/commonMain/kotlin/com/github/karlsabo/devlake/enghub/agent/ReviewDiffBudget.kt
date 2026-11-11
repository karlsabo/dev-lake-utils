package com.github.karlsabo.devlake.enghub.agent

private const val LINE_END = "\n"

/** Keeps changes whose baseline content cannot be recovered by reading the current file. */
private const val NON_ADDED = "--diff-filter=a"

/** Added files can be read in full after their diff is cut. */
private const val ADDED_ONLY = "--diff-filter=A"

/**
 * Cuts the reviewer's diff to [maxChars] without dropping baseline content from changed paths.
 *
 * Non-added diffs are reserved in full; only added-file diffs may be truncated. A patch that fits is
 * returned untouched. The reviewer can read added files but cannot reconstruct removed lines by reading
 * modified or deleted files.
 *
 * @throws IllegalStateException when the non-added diff does not fit.
 */
internal fun boundedReviewDiff(
    maxChars: Int,
    patch: String,
    nonAdded: List<WorkingTreeChange>,
    filteredDiff: (String) -> String,
): BoundedText = when {
    patch.length <= maxChars -> BoundedText(patch, truncated = false)
    nonAdded.isEmpty() -> truncateAtLineBoundary(patch, maxChars)
    else -> keepBaselineDiff(maxChars, nonAdded, filteredDiff)
}

/** Reserves baseline-bearing diffs and spends the remaining budget on added paths. */
private fun keepBaselineDiff(
    maxChars: Int,
    nonAdded: List<WorkingTreeChange>,
    filteredDiff: (String) -> String,
): BoundedText {
    val baselineDiff = filteredDiff(NON_ADDED) + LINE_END
    val budget = maxChars - baselineDiff.length
    check(budget >= 0) { uninspectableBaselineDiff(maxChars, nonAdded) }
    val addedPrefix = if (budget == 0) "" else truncateAtLineBoundary(filteredDiff(ADDED_ONLY), budget).text
    return BoundedText(addedPrefix + baselineDiff, truncated = true)
}

private fun uninspectableBaselineDiff(
    maxChars: Int,
    nonAdded: List<WorkingTreeChange>,
): String = "Eng Hub cannot fit the removed content of ${nonAdded.size} modified or deleted path(s) into the " +
    "reviewer's $maxChars-character diff budget: " +
    "${nonAdded.joinToString(", ") { change -> change.path }}. Failing the review rather than letting the " +
    "reviewer pass on baseline content it cannot inspect."
