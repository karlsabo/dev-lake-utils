package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.git.GitCommandApi
import com.github.karlsabo.git.GitCommandException
import com.github.karlsabo.git.GitCommandService

/** Prompt budget for the verified diff; the reviewer inspects surviving files with its `read` tool. */
private const val MAX_REVIEW_PATCH_CHARS = 24_000

private const val NUL = '\u0000'
private const val PATH_STATUS_PAIR = 2
private const val NO_RENAMES = "--no-renames"
private const val RELATIVE_PATHS = "--relative"
private const val NUL_SEPARATED = "-z"
private const val NO_COLOR = "--no-color"
private const val NO_EXT_DIFF = "--no-ext-diff"
private const val NO_TEXTCONV = "--no-textconv"

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
    private val binaryChanges = GitBinaryChanges(workingDirectory, git)

    override fun baseline(): WorkingTreeBaseline = gitRead("record the baseline commit") {
        WorkingTreeBaseline(git.revParse(workingDirectory, "--verify", "HEAD"))
    }

    override fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport = gitRead(
        "read the working-tree changes",
    ) {
        val revision = baseline.revision
        val untracked = untrackedChanges()
        val changes = (trackedChanges(revision) + untracked)
            .distinctBy { change -> change.path }
            .sortedBy { change -> change.path }
        // The prompt is where a changed path becomes discoverable and an untracked path has no diff content, so a
        // path the prompt drops could be neither found nor read. More paths than the prompt lists therefore fail
        // the review instead of handing over a truncated list.
        check(changes.size <= maxChangedPaths) { tooManyChangedPaths(changes.size, maxChangedPaths) }
        requireInspectableChanges(revision, changes, untracked)
        val deleted = changes.filter { change -> change.kind == WorkingTreeChangeKind.DELETED }
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
     * Fails when a changed path's content cannot reach the reviewer, because no reviewer tool can show it.
     *
     * Git renders binary content as a one-line `Binary files ... differ` marker instead of the bytes, and an
     * untracked binary path appears in no diff at all, so neither side of such a change is available: a modified
     * path loses its original content, a deleted path cannot be read back, and an untracked path was never in a
     * diff. The reviewer's `read` tool is a UTF-8 text tool, so it cannot stand in for the missing bytes either.
     * Textconv is not a way out: it runs a command from the repository's own configuration, which is outside the
     * reviewer's read-only boundary, and its output is a rendering rather than the bytes. The review therefore
     * fails closed instead of handing over a change the reviewer could only pass unexamined.
     *
     * Text review is unaffected: only paths whose content git will not diff as text are named, and a mode-only
     * change carries its mode lines in the patch.
     */
    private fun requireInspectableChanges(
        revision: String,
        changes: List<WorkingTreeChange>,
        untracked: List<WorkingTreeChange>,
    ) {
        val uninspectablePaths = binaryChanges.uninspectablePaths(
            revision = revision,
            untrackedPaths = untracked.map { change -> change.path },
        )
        val uninspectable = changes.filter { change -> change.path in uninspectablePaths }
        check(uninspectable.isEmpty()) { uninspectableBinaryChanges(uninspectable) }
    }

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
internal fun splitNulPaths(output: String): List<String> = output.split(NUL).filter { path -> path.isNotEmpty() }

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
