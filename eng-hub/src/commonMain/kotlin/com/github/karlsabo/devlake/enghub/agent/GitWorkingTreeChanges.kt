package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.git.GitCommandApi
import com.github.karlsabo.git.GitCommandException
import com.github.karlsabo.git.GitCommandService

/** Prompt budget for the verified diff; the reviewer inspects surviving files with its `read` tool. */
private const val MAX_REVIEW_PATCH_CHARS = 24_000

private const val NUL = '\u0000'
private const val PATH_STATUS_PAIR = 2
private const val NUMSTAT_FIELDS = 3
private const val NO_RENAMES = "--no-renames"
private const val RELATIVE_PATHS = "--relative"
private const val NUL_SEPARATED = "-z"
private const val NO_COLOR = "--no-color"
private const val NO_EXT_DIFF = "--no-ext-diff"
private const val NO_TEXTCONV = "--no-textconv"
private const val NUMSTAT = "--numstat"

/** The count git prints in `--numstat` for a path whose content it will not diff as text. */
private const val BINARY_COUNT = "-"

/**
 * Reads working-tree changes with git, relative to the reviewed working directory.
 *
 * Every command here is a read-only git query: it lists paths and diffs content that the working tree
 * already holds. This class never runs the agent's `bash` tool and never hands the reviewer a command
 * surface of its own.
 */
class GitWorkingTreeChanges(
    private val workingDirectory: String,
    private val maxPatchChars: Int = MAX_REVIEW_PATCH_CHARS,
    private val maxChangedPaths: Int = MAX_PROMPT_CHANGED_PATHS,
    private val git: GitCommandApi = GitCommandService(),
) : WorkingTreeChanges {
    override fun baseline(): WorkingTreeBaseline = gitRead("record the baseline commit") {
        WorkingTreeBaseline(git.revParse(workingDirectory, "--verify", "HEAD"))
    }

    override fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport = gitRead(
        "read the working-tree changes",
    ) {
        val revision = baseline.revision
        val changes = readChanges(revision)
        // The prompt is where a changed path becomes discoverable and an untracked path has no diff content, so a
        // path the prompt drops could be neither found nor read. More paths than the prompt lists therefore fail
        // the review instead of handing over a truncated list.
        check(changes.size <= maxChangedPaths) { tooManyChangedPaths(changes.size, maxChangedPaths) }
        val deleted = changes.filter { change -> change.kind == WorkingTreeChangeKind.DELETED }
        requireInspectableDeletions(revision, deleted)
        val patch = boundedReviewDiff(
            maxChars = maxPatchChars,
            patch = readPatch(revision),
            deleted = deleted,
            filteredDiff = { filter -> readPatch(revision, filter) },
        )
        WorkingTreeChangeReport(
            baselineRevision = revision,
            changes = changes,
            patch = patch.text,
            patchTruncated = patch.truncated,
        )
    }

    private fun readChanges(revision: String): List<WorkingTreeChange> = (trackedChanges(revision) + untrackedChanges())
        .distinctBy { change -> change.path }
        .sortedBy { change -> change.path }

    private fun trackedChanges(revision: String): List<WorkingTreeChange> = parseNameStatus(
        git.diff(workingDirectory, "--name-status", RELATIVE_PATHS, NO_RENAMES, NUL_SEPARATED, revision, "--"),
    )

    private fun untrackedChanges(): List<WorkingTreeChange> = splitNulPaths(
        git.execute(workingDirectory, "ls-files", "--others", "--exclude-standard", NUL_SEPARATED),
    ).map { path -> WorkingTreeChange(path, WorkingTreeChangeKind.ADDED) }

    /** Diffs [revision] against the working tree with every status, which is the review diff when it fits. */
    private fun readPatch(revision: String): String = git.diff(
        workingDirectory,
        NO_COLOR,
        NO_EXT_DIFF,
        NO_TEXTCONV,
        RELATIVE_PATHS,
        NO_RENAMES,
        revision,
        "--",
    )

    /**
     * Diffs [revision] against the working tree keeping only the changes whose status [filter] selects.
     *
     * [boundedReviewDiff] splits the prompt budget with these filters, so the removed content of deleted
     * paths can be read separately from the rest of the diff.
     */
    private fun readPatch(
        revision: String,
        filter: String,
    ): String = git.diff(
        workingDirectory,
        NO_COLOR,
        NO_EXT_DIFF,
        NO_TEXTCONV,
        RELATIVE_PATHS,
        NO_RENAMES,
        filter,
        revision,
        "--",
    )

    /**
     * Fails when a deleted path's removed content is not in the diff at all, whatever its size.
     *
     * Git renders binary content as a one-line `Binary files ... differ` marker, and the reviewer cannot read a
     * deleted path back, so a binary deletion would reach the reviewer as a change it can neither see nor open.
     * A textconv driver could render some of those paths, but it runs a command from the repository's own
     * configuration, which is outside the reviewer's read-only boundary, and its output is a rendering rather than
     * the removed bytes. The review therefore fails closed instead of handing over a deletion it cannot inspect.
     */
    private fun requireInspectableDeletions(
        revision: String,
        deleted: List<WorkingTreeChange>,
    ) {
        if (deleted.isEmpty()) return
        val binaryDeletions = binaryDeletedPaths(revision)
        check(binaryDeletions.isEmpty()) { uninspectableBinaryDeletions(binaryDeletions) }
    }

    /**
     * Lists deleted paths whose removed content git reports only as a binary marker.
     *
     * The flags match [readPatch]'s on purpose: this query must classify a path as binary under the same
     * conditions that decide whether the patch carries its removed content, so a driver cannot make one
     * inspectable and the other not.
     */
    private fun binaryDeletedPaths(revision: String): List<String> = splitNulPaths(
        git.diff(
            workingDirectory,
            NUMSTAT,
            NUL_SEPARATED,
            NO_EXT_DIFF,
            NO_TEXTCONV,
            RELATIVE_PATHS,
            NO_RENAMES,
            DELETED_ONLY,
            revision,
            "--",
        ),
    ).mapNotNull(::binaryPath)

    private fun <T> gitRead(
        action: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: GitCommandException) {
        throw IllegalStateException(
            "Eng Hub could not $action in $workingDirectory. The implement-then-review flow needs a git " +
                "working tree with at least one commit. ${failure.message}",
            failure,
        )
    }
}

/** Parses `git diff --name-status -z`, which emits one status and one path per change. */
private fun parseNameStatus(output: String): List<WorkingTreeChange> {
    val entries = splitNulPaths(output)
    require(entries.size % PATH_STATUS_PAIR == 0) {
        "Unexpected git name-status output: $output"
    }
    return entries.chunked(PATH_STATUS_PAIR).map { entry ->
        WorkingTreeChange(entry[1], kindOf(entry[0]))
    }
}

/** Splits NUL-separated git path output, dropping the empty tail a trailing separator leaves. */
private fun splitNulPaths(output: String): List<String> = output.split(NUL).filter { path -> path.isNotEmpty() }

/**
 * Reads one `git diff --numstat -z` record, returning its path only when git counts no textual lines.
 *
 * The path is the last field so a path containing tabs survives the split.
 */
private fun binaryPath(record: String): String? {
    val fields = record.split('\t', limit = NUMSTAT_FIELDS)
    if (fields.size < NUMSTAT_FIELDS) return null
    val (added, removed, path) = fields
    return path.takeIf {
        added == BINARY_COUNT && removed == BINARY_COUNT && path.isNotEmpty()
    }
}

/**
 * Maps a git name-status code to a reviewable kind.
 *
 * Renames are disabled with `--no-renames`, so a rename arrives as a delete plus an add. Any remaining
 * code (`M`, `T`, `U`, ...) is reported as a modification so no changed path is dropped.
 */
private fun kindOf(status: String): WorkingTreeChangeKind = when (status.firstOrNull()) {
    'A', 'C' -> WorkingTreeChangeKind.ADDED
    'D' -> WorkingTreeChangeKind.DELETED
    else -> WorkingTreeChangeKind.MODIFIED
}
