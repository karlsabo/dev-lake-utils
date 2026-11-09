package com.github.karlsabo.devlake.enghub.agent

private const val LINE_END = "\n"

/**
 * Isolates the removed content of deleted paths, which must survive truncation.
 */
internal const val DELETED_ONLY = "--diff-filter=D"

/** Isolates the rest of the diff, which truncation may cut. */
private const val SURVIVING_ONLY = "--diff-filter=d"

/**
 * Cuts the reviewer's diff to [maxChars] without dropping what a deleted path removed.
 *
 * The reviewer can re-read a surviving file with its `read` tool and can never read a deleted one, so a
 * deletion that truncation cuts off is a change it cannot inspect. A [patch] that fits is returned
 * untouched; a larger one is cut at a line boundary only after the removed content of [deleted] has been
 * reserved in full, and the surviving diff is cut to whatever budget is left. [filteredDiff] supplies both
 * halves of that split from the same diff options.
 *
 * @throws IllegalStateException when the removed content alone does not fit, so the review fails closed
 *   instead of asking the reviewer to judge a deletion it cannot see.
 */
internal fun boundedReviewDiff(
    maxChars: Int,
    patch: String,
    deleted: List<WorkingTreeChange>,
    filteredDiff: (String) -> String,
): BoundedText = when {
    patch.length <= maxChars -> BoundedText(patch, truncated = false)
    deleted.isEmpty() -> truncateAtLineBoundary(patch, maxChars)
    else -> keepRemovals(maxChars, deleted, filteredDiff)
}

/** Keeps the deleted content whole and spends what is left of the budget on the surviving diff. */
private fun keepRemovals(
    maxChars: Int,
    deleted: List<WorkingTreeChange>,
    filteredDiff: (String) -> String,
): BoundedText {
    val removals = filteredDiff(DELETED_ONLY) + LINE_END
    val budget = maxChars - removals.length
    check(budget >= 0) { uninspectableDeletions(maxChars, deleted) }
    return BoundedText(survivingPrefix(filteredDiff, budget) + removals, truncated = true)
}

private fun survivingPrefix(
    filteredDiff: (String) -> String,
    budget: Int,
): String = if (budget == 0) "" else truncateAtLineBoundary(filteredDiff(SURVIVING_ONLY), budget).text

private fun uninspectableDeletions(
    maxChars: Int,
    deleted: List<WorkingTreeChange>,
): String = "Eng Hub cannot fit the removed content of ${deleted.size} deleted path(s) into the reviewer's " +
    "$maxChars-character diff budget, and a deleted path cannot be read back: " +
    "${deleted.joinToString(", ") { change -> change.path }}. Failing the review rather than letting the " +
    "reviewer pass on deletions it cannot inspect."
