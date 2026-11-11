package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.devlake.enghub.isSymbolicLink
import com.github.karlsabo.git.GitCommandApi
import com.github.karlsabo.git.GitCommandException
import com.github.karlsabo.git.GitCommandService
import kotlinx.io.files.Path

/** Prompt budget for the verified diff; the reviewer inspects surviving files with its `read` tool. */
private const val MAX_REVIEW_PATCH_CHARS = 24_000

private const val NUL = '\u0000'
private const val PATH_STATUS_PAIR = 2
private const val OPAQUE_DIRECTORY_SUFFIX = '/'
private const val NO_RENAMES = "--no-renames"
private const val RELATIVE_PATHS = "--relative"
private const val NUL_SEPARATED = "-z"
private const val NO_COLOR = "--no-color"
private const val NO_EXT_DIFF = "--no-ext-diff"
private const val NO_TEXTCONV = "--no-textconv"
private const val SYMLINK_MODE = "120000"
private val RAW_MODES = Regex("^:([0-7]{6}) ([0-7]{6}) ")

/**
 * Reads working-tree changes with read-only git queries relative to the reviewed working directory.
 *
 * No command surface is exposed to the reviewer, and a working tree whose git config would run a filter
 * command is refused, because git executes that command while diffing.
 */
class GitWorkingTreeChanges(
    private val workingDirectory: String,
    private val maxPatchChars: Int = MAX_REVIEW_PATCH_CHARS,
    private val maxChangedPaths: Int = MAX_PROMPT_CHANGED_PATHS,
    private val git: GitCommandApi = GitCommandService(),
) : WorkingTreeChanges {
    private val binaryChanges = GitBinaryChanges(workingDirectory, git)
    private val configuredFilters = GitConfiguredFilters(workingDirectory, git)
    private val indexInspection = GitIndexInspection(workingDirectory, git)

    override fun baseline(): WorkingTreeBaseline = gitRead("record the baseline commit") {
        WorkingTreeBaseline(git.revParse(workingDirectory, "--verify", "HEAD"))
    }

    override fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport = gitRead(
        "read the working-tree changes",
    ) {
        val revision = baseline.revision
        indexInspection.requireVisibleTrackedFiles()
        val untracked = untrackedChanges()
        // Both guards run before any diff-bearing query: git applies a configured clean filter while diffing,
        // which could run repository code and hide a changed line. The guards read only attributes and config.
        requireInspectableUntrackedPaths(untracked)
        configuredFilters.requireNoConfiguredCommands(untracked.map { change -> change.path })
        val changes = (trackedChanges(revision) + untracked)
            .distinctBy { change -> change.path }
            .sortedBy { change -> change.path }
        // A path the prompt drops could be neither discovered nor read, so overflow fails instead of truncating.
        check(changes.size <= maxChangedPaths) { tooManyChangedPaths(changes.size, maxChangedPaths) }
        indexInspection.requireInspectableGitlinks(changes)
        requireInspectableChanges(revision, changes, untracked)
        val nonAdded = changes.filter { change -> change.kind != WorkingTreeChangeKind.ADDED }
        val fullPatch = readPatch(revision)
        if (fullPatch.length > maxPatchChars) requireUncutSymlinkTargets(revision)
        val patch = boundedReviewDiff(
            maxChars = maxPatchChars,
            patch = fullPatch,
            nonAdded = nonAdded,
            filteredDiff = { filter -> readPatch(revision, filter) },
        )
        WorkingTreeChangeReport(
            baselineRevision = revision,
            changes = changes,
            patch = patch.text,
            patchTruncated = patch.truncated,
        )
    }

    /** A truncated diff cannot be repaired by `read` for either side of a tracked symlink. */
    private fun requireUncutSymlinkTargets(revision: String) {
        val raw = splitNulPaths(
            git.diff(
                workingDirectory,
                "--raw",
                NO_EXT_DIFF,
                NO_TEXTCONV,
                NUL_SEPARATED,
                RELATIVE_PATHS,
                NO_RENAMES,
                revision,
                "--",
            ),
        )
        check(raw.size % PATH_STATUS_PAIR == 0) { "Unexpected git raw diff output" }
        val symlinks = raw.chunked(PATH_STATUS_PAIR).mapNotNull { entry ->
            val modes = requireNotNull(RAW_MODES.find(entry[0])) { "Unexpected git raw diff header" }
            entry[1].takeIf { SYMLINK_MODE in modes.groupValues.drop(1) }
        }
        check(symlinks.isEmpty()) {
            "Eng Hub cannot fit the tracked symlink target diff into the reviewer's $maxPatchChars-character " +
                "diff budget; read follows symlinks instead of showing their targets: ${symlinks.joinToString()}. " +
                "Failing the review instead."
        }
    }

    private fun trackedChanges(revision: String): List<WorkingTreeChange> = parseNameStatus(
        git.diff(workingDirectory, "--name-status", RELATIVE_PATHS, NO_RENAMES, NUL_SEPARATED, revision, "--"),
    )

    private fun untrackedChanges(): List<WorkingTreeChange> = splitNulPaths(
        git.executeRaw(workingDirectory, listOf("ls-files", "--others", "--exclude-standard", NUL_SEPARATED)),
    ).map { path -> WorkingTreeChange(path, WorkingTreeChangeKind.ADDED) }

    /**
     * Fails when git reports an untracked path no reviewer tool can inspect: an opaque directory (e.g. a nested
     * repository) whose files appear in no diff, or a symlink that `read` follows instead of showing.
     */
    private fun requireInspectableUntrackedPaths(untracked: List<WorkingTreeChange>) {
        val opaque = untracked.filter { change -> change.path.endsWith(OPAQUE_DIRECTORY_SUFFIX) }
            .map { change -> change.path }
        check(opaque.isEmpty()) { opaqueUntrackedDirectories(opaque) }
        val symlinks = untracked.filter { change -> Path(workingDirectory, change.path).isSymbolicLink() }
            .map { change -> change.path }
        check(symlinks.isEmpty()) { uninspectableUntrackedSymlinks(symlinks) }
    }

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

    /** Diffs [revision] against the working tree keeping only the changes whose status [filter] selects. */
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
     * Fails when a changed path's content cannot reach the reviewer.
     *
     * Git renders binary content as a `Binary files ... differ` marker and omits untracked paths from every
     * diff, and the reviewer's `read` tool is UTF-8 text, so neither side of such a change is inspectable.
     * Textconv is not a way out: it runs a repository-configured command outside the read-only boundary.
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

/**
 * Splits NUL-separated git output, dropping only the trailing empty field.
 *
 * Interior empty fields are kept because `git check-attr -z` writes one for an empty attribute value, and
 * dropping it would shift the following fields.
 */
internal fun splitNulPaths(output: String): List<String> {
    if (output.isEmpty()) return emptyList()
    val fields = output.split(NUL)
    return if (fields.last().isEmpty()) fields.dropLast(1) else fields
}

/**
 * Maps a git name-status code to a reviewable kind.
 *
 * Unknown codes map to MODIFIED so no changed path is dropped; renames arrive as a delete plus an add.
 */
private fun kindOf(status: String): WorkingTreeChangeKind = when (status.firstOrNull()) {
    'A', 'C' -> WorkingTreeChangeKind.ADDED
    'D' -> WorkingTreeChangeKind.DELETED
    else -> WorkingTreeChangeKind.MODIFIED
}
