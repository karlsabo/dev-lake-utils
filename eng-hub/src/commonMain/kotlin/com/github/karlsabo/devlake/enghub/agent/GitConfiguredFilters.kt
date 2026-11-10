package com.github.karlsabo.devlake.enghub.agent

import com.github.karlsabo.git.GitCommandApi

private const val NUL_SEPARATED = "-z"
private const val NULL_SEPARATED = "--null"
private const val LIST_CONFIG = "--list"
private const val INCLUDE_CONFIG_FILES = "--includes"
private const val END_OF_OPTIONS = "--"
private const val FILTER_ATTRIBUTE = "filter"
private const val UNSPECIFIED_ATTRIBUTE = "unspecified"
private const val UNSET_ATTRIBUTE = "unset"
private const val ATTRIBUTE_FIELDS = 3
private const val VALUE_FIELD = 2
private const val PATHS_PER_QUERY = 64
private const val CONFIG_VALUE_SEPARATOR = '\n'
private const val CLEAN_COMMAND = ".clean"
private const val PROCESS_COMMAND = ".process"

/**
 * Fails a review when a repository-configured filter command could run or hide a change while git reads the
 * working tree.
 *
 * `git diff` and `git hash-object` pass a working-tree file through its clean filter before comparing it with
 * the baseline. That command comes from the repository's config, so change discovery would execute untrusted
 * code, and a filter that rewrites lines can hide a real change from the path list and the diff. `--no-ext-diff`
 * and `--no-textconv` do not disable it. All queries here are metadata-only and run nothing.
 */
internal class GitConfiguredFilters(
    private val workingDirectory: String,
    private val git: GitCommandApi,
) {
    /**
     * Fails when any tracked path or [untrackedPaths] path resolves to a filter with a configured command.
     *
     * Every working-tree path is checked: a filter that hides a line can keep a changed path out of the set.
     */
    fun requireNoConfiguredCommands(untrackedPaths: List<String>) {
        val drivers = filterDrivers(workingTreePaths(untrackedPaths))
        if (drivers.isEmpty()) return
        val commands = configuredCommands(drivers)
        check(commands.isEmpty()) { configuredFilterCommands(commands) }
    }

    private fun workingTreePaths(untrackedPaths: List<String>): List<String> = splitNulPaths(
        git.executeRaw(workingDirectory, listOf("ls-files", NUL_SEPARATED)),
    ) + untrackedPaths

    private fun filterDrivers(paths: List<String>): Set<String> = paths
        .chunked(PATHS_PER_QUERY)
        .flatMap(::filterDriverValues)
        .filter { value -> value != UNSPECIFIED_ATTRIBUTE && value != UNSET_ATTRIBUTE }
        .toSet()

    /**
     * Reads `git check-attr -z filter`, one `path`, `attribute`, `value` triple per path.
     *
     * The value is empty for an attribute set to the empty string (`filter=`), so whole triples are read;
     * dropping an empty field would shift every later triple and let the wrong filter run unseen.
     */
    private fun filterDriverValues(paths: List<String>): List<String> {
        val fields = splitNulPaths(
            git.executeRaw(
                workingDirectory,
                listOf("check-attr", NUL_SEPARATED, FILTER_ATTRIBUTE, END_OF_OPTIONS) + paths,
            ),
        )
        check(fields.size % ATTRIBUTE_FIELDS == 0) {
            "Unexpected git check-attr output: ${fields.size} field(s) for ${paths.size} path(s)."
        }
        return fields.chunked(ATTRIBUTE_FIELDS).map { entry -> entry[VALUE_FIELD] }
    }

    /**
     * Lists the configured filter commands for [drivers]. Git treats an empty value as unset, but a
     * blank-looking value still reaches the shell, so every non-empty value counts.
     */
    private fun configuredCommands(drivers: Set<String>): List<String> {
        val config = configValues()
        return drivers
            .flatMap { driver -> listOf("filter.$driver$CLEAN_COMMAND", "filter.$driver$PROCESS_COMMAND") }
            .filter { key -> config[key]?.isNotEmpty() == true }
            .sorted()
    }

    /** Reads every config value, including the ones a `-c` or an include directive contributes. */
    private fun configValues(): Map<String, String> = splitNulPaths(
        git.executeRaw(workingDirectory, listOf("config", LIST_CONFIG, INCLUDE_CONFIG_FILES, NULL_SEPARATED)),
    ).associate { entry ->
        entry.substringBefore(CONFIG_VALUE_SEPARATOR) to entry.substringAfter(CONFIG_VALUE_SEPARATOR, "")
    }
}

/**
 * Explains a refusal to review a working tree whose git config would run a filter command.
 *
 * @param commands the configured filter keys git would run, in `filter.<driver>.<command>` form.
 */
internal fun configuredFilterCommands(commands: List<String>): String {
    val listed = commands.joinToString(", ")
    return "Eng Hub found ${commands.size} configured git filter command(s) that git would run while reading " +
        "the working-tree changes: $listed. A clean filter rewrites a working-tree file before git compares it " +
        "with the baseline, so it can hide a changed line from the path list and the diff, and running it is " +
        "outside the reviewer's read-only boundary. Failing the review instead of running repository-configured " +
        "filters."
}
