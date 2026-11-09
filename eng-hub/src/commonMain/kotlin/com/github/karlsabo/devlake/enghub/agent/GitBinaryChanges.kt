package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.git.GitCommandApi
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private const val NUMSTAT = "--numstat"
private const val NUL_SEPARATED = "-z"
private const val NO_EXT_DIFF = "--no-ext-diff"
private const val NO_TEXTCONV = "--no-textconv"
private const val RELATIVE_PATHS = "--relative"
private const val NO_RENAMES = "--no-renames"
private const val END_OF_OPTIONS = "--"

/** Makes a changed path a literal pathspec, so a name containing `*` or `[` matches only itself. */
private const val LITERAL_PATHSPEC = ":(literal)"

/** The count git prints in `--numstat` for a path whose content it will not diff as text. */
private const val BINARY_COUNT = "-"

/** Git's own binary sniff reads at most this many leading bytes, looking for a NUL. */
private const val BINARY_SNIFF_BYTES = 8_000L
private const val NUL_BYTE = 0.toByte()
private const val NUMSTAT_FIELDS = 3
private const val PATH_FIELD_SEPARATOR = '\t'
private const val METADATA_FIELD_SEPARATOR = ' '

/**
 * Finds changed paths whose content never reaches the reviewer, because no reviewer tool can show it.
 *
 * The reviewer's `read` tool is a UTF-8 text tool and its only inspection surface. Git renders binary content
 * as a one-line `Binary files ... differ` marker instead of the bytes, so a tracked binary change loses the
 * content of at least one side, and an untracked binary path appears in no diff at all. Either way the reviewer
 * would judge a change it never saw.
 *
 * Every query here is read-only: it lists paths, compares blob ids, and sniffs leading bytes. No hook, no
 * textconv driver, and no shell of our own is involved: a textconv driver is deliberately off on every diff,
 * because it renders content with a repository-configured command outside the reviewer's boundary instead of
 * showing the bytes. A configured clean or process filter never reaches these queries either, because
 * [GitWorkingTreeChanges] refuses that working tree before any diff or hash runs. Blob ids come from git's own
 * hashing, so they compare like with like.
 */
internal class GitBinaryChanges(
    private val workingDirectory: String,
    private val git: GitCommandApi,
) {
    /**
     * Lists the changed paths whose content the reviewer cannot inspect.
     *
     * @param revision the baseline commit the changes are read against.
     * @param untrackedPaths untracked paths, which no diff can classify.
     */
    fun uninspectablePaths(
        revision: String,
        untrackedPaths: List<String>,
    ): Set<String> = (changedBinaryPaths(revision) + untrackedBinaryPaths(untrackedPaths)).toSet()

    /**
     * Lists changed tracked paths, whatever their status, whose diff carries a binary marker instead of bytes.
     *
     * `--numstat` is the locale-independent signal: a path whose content git will not diff as text counts no
     * lines. It also reports no line counts for a mode-only change, whose content is unchanged and whose mode
     * lines the patch does carry, so each candidate's blob is compared with the baseline and only a real
     * content change counts. The flags match the reviewer diff's on purpose, so a driver cannot make one query
     * see content and the other not.
     */
    private fun changedBinaryPaths(revision: String): List<String> = numstatBinaryPaths(revision)
        .filter { path -> contentDiffers(revision, path) }

    private fun numstatBinaryPaths(revision: String): List<String> = splitNulPaths(
        git.diff(
            workingDirectory,
            NUMSTAT,
            NUL_SEPARATED,
            NO_EXT_DIFF,
            NO_TEXTCONV,
            RELATIVE_PATHS,
            NO_RENAMES,
            revision,
            END_OF_OPTIONS,
        ),
    ).mapNotNull(::binaryPath)

    /**
     * Whether the bytes of [path] differ from [revision], so a binary marker hides a real content change.
     *
     * A path with no working-tree file is a deletion, which git describes only as a marker, so it counts as a
     * content change. Anything without a comparable blob counts the same way: a change that cannot be shown is
     * exactly what this check fails on.
     */
    private fun contentDiffers(
        revision: String,
        path: String,
    ): Boolean {
        val workingTreeBlob = workingTreeBlob(path)
        return workingTreeBlob == null || workingTreeBlob != baselineBlob(revision, path)
    }

    private fun workingTreeBlob(path: String): String? = if (isRegularFile(path)) {
        git.execute(workingDirectory, "hash-object", END_OF_OPTIONS, path)
    } else {
        null
    }

    /**
     * Reads the baseline blob id of [path] from the [revision] tree, or null when the path is new.
     *
     * `ls-tree` prints `<mode> <type> <blob>\t<path>` per entry, so the blob is the third metadata field.
     */
    private fun baselineBlob(
        revision: String,
        path: String,
    ): String? = splitNulPaths(
        git.execute(workingDirectory, "ls-tree", NUL_SEPARATED, revision, END_OF_OPTIONS, LITERAL_PATHSPEC + path),
    ).firstOrNull()
        ?.substringBefore(PATH_FIELD_SEPARATOR)
        ?.substringAfterLast(METADATA_FIELD_SEPARATOR)

    private fun isRegularFile(path: String): Boolean {
        val metadata = SystemFileSystem.metadataOrNull(Path(workingDirectory, path))
        return metadata?.isRegularFile == true
    }

    /**
     * Lists untracked paths whose bytes the reviewer's text `read` tool cannot show.
     *
     * Untracked paths appear in no diff, so git never classifies them; this applies git's own heuristic instead,
     * which is a NUL byte among the leading bytes.
     */
    private fun untrackedBinaryPaths(paths: List<String>): List<String> = paths.filter(::sniffsAsBinary)

    private fun sniffsAsBinary(path: String): Boolean {
        if (!isRegularFile(path)) return false
        return SystemFileSystem.source(Path(workingDirectory, path)).buffered().use { source ->
            val prefix = Buffer()
            source.readAtMostTo(prefix, BINARY_SNIFF_BYTES)
            prefix.readByteArray().any { byte -> byte == NUL_BYTE }
        }
    }
}

/**
 * Reads one `git diff --numstat -z` record, returning its path only when git counts no textual lines.
 *
 * The path is the last field so a path containing tabs survives the split.
 */
private fun binaryPath(record: String): String? {
    val fields = record.split(PATH_FIELD_SEPARATOR, limit = NUMSTAT_FIELDS)
    if (fields.size < NUMSTAT_FIELDS) return null
    val (added, removed, path) = fields
    return path.takeIf { added == BINARY_COUNT && removed == BINARY_COUNT && path.isNotEmpty() }
}
