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
 * `git diff` and `git hash-object` pass a working-tree file through the clean filter its `filter` attribute
 * names before comparing it with the baseline blob. That filter is a command from the repository's own
 * configuration, so change discovery would execute untrusted code, and a filter that rewrites or drops lines
 * can keep a real change out of `--name-status`, `--numstat`, and the patch. `--no-ext-diff` and
 * `--no-textconv` do not disable it. Reading attributes and config values runs nothing, so this guard answers
 * first and the review fails closed when a command is configured.
 *
 * Every query here is metadata only: `git ls-files` lists paths, `git check-attr` reads attribute files, and
 * `git config --list` reads configuration. No configured command is ever executed.
 */
internal class GitConfiguredFilters(
    private val workingDirectory: String,
    private val git: GitCommandApi,
) {
    /**
     * Fails when any tracked path or [untrackedPaths] path resolves to a filter with a configured command.
     *
     * Every working-tree path is checked, not only the changed ones, because a filter that hides a line is
     * exactly what keeps a changed path out of the change set in the first place.
     */
    fun requireNoConfiguredCommands(untrackedPaths: List<String>) {
        val drivers = filterDrivers(workingTreePaths(untrackedPaths))
        if (drivers.isEmpty()) return
        val commands = configuredCommands(drivers)
        check(commands.isEmpty()) { configuredFilterCommands(commands) }
    }

    private fun workingTreePaths(untrackedPaths: List<String>): List<String> = splitNulPaths(
        git.execute(workingDirectory, "ls-files", NUL_SEPARATED),
    ) + untrackedPaths

    private fun filterDrivers(paths: List<String>): Set<String> = paths
        .chunked(PATHS_PER_QUERY)
        .flatMap(::filterDriverValues)
        .filter { value -> value != UNSPECIFIED_ATTRIBUTE && value != UNSET_ATTRIBUTE }
        .toSet()

    /** Reads `git check-attr -z filter`, whose output is a `path`, `attribute`, `value` triple per path. */
    private fun filterDriverValues(paths: List<String>): List<String> = splitNulPaths(
        git.executeArgs(
            workingDirectory,
            listOf("check-attr", NUL_SEPARATED, FILTER_ATTRIBUTE, END_OF_OPTIONS) + paths,
        ),
    ).chunked(ATTRIBUTE_FIELDS).mapNotNull { entry -> entry.getOrNull(VALUE_FIELD) }

    /**
     * Lists the configured filter commands for [drivers].
     *
     * Git treats an empty value as unset, but a blank-looking value still reaches the shell, so every non-empty
     * value counts as a command.
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
        git.execute(workingDirectory, "config", LIST_CONFIG, INCLUDE_CONFIG_FILES, NULL_SEPARATED),
    ).associate { entry ->
        entry.substringBefore(CONFIG_VALUE_SEPARATOR) to entry.substringAfter(CONFIG_VALUE_SEPARATOR, "")
    }
}

/**
 * Explains a refusal to review a working tree whose git configuration would run a filter command.
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
