package com.github.karlsabo.intellij

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

private val logger = KotlinLogging.logger {}

private const val MODULE_DIR_PREFIX = "file://\$MODULE_DIR$/"
private const val EXCLUDE_FOLDER_TAG = "excludeFolder"

/** Directories that are safe to exclude from IntelliJ indexing across most projects. */
val DEFAULT_EXCLUDE_DIRECTORIES: List<String> = listOf(
    ".buildkite",
    ".cache",
    ".chainlit",
    ".codex",
    ".conductor",
    ".cursor",
    ".direnv",
    ".fleet",
    ".git",
    ".github",
    ".husky",
    ".hypothesis",
    ".mypy_cache",
    ".nox",
    ".pants.d",
    ".plugins-venv",
    ".pyre",
    ".pytest_cache",
    ".ruff_cache",
    ".run",
    ".tox",
    ".turbo",
    ".upgrade-venv",
    ".venv",
    ".vscode",
    ".yarn",
    "buildCache",
    "dist",
    "docs/_build",
    "htmlcov",
    "node_modules",
    "out",
)

data class ImlUpdateResult(
    val added: List<String>,
    val alreadyExcluded: List<String>,
    val notApplied: List<String>,
)

/**
 * Adds `<excludeFolder>` entries to an IntelliJ `.iml` file for the given directories.
 *
 * Existing exclusions are preserved and not duplicated. New entries are inserted
 * before the closing `</content>` tag with matching indentation.
 *
 * @param imlPath path to the `.iml` file
 * @param directoriesToExclude directory names relative to the module root
 * @return result describing what was added, already present, or could not be applied
 */
fun addExcludeFoldersToIml(
    imlPath: Path,
    directoriesToExclude: List<String> = DEFAULT_EXCLUDE_DIRECTORIES,
): ImlUpdateResult {
    require(SystemFileSystem.exists(imlPath)) { "IML file not found: $imlPath" }

    val content = SystemFileSystem.source(imlPath).buffered().readString()
    val existingUrls = parseExcludedFolderUrls(content)

    val alreadyExcluded = mutableListOf<String>()
    val toAdd = mutableListOf<String>()

    for (dir in directoriesToExclude) {
        val url = "$MODULE_DIR_PREFIX$dir"
        if (url in existingUrls) {
            alreadyExcluded.add(dir)
        } else {
            toAdd.add(dir)
        }
    }

    if (toAdd.isEmpty()) {
        logger.info { "No new exclusions to add to $imlPath" }
        return ImlUpdateResult(added = emptyList(), alreadyExcluded = alreadyExcluded, notApplied = emptyList())
    }

    val updatedContent = insertExcludeFolders(content, toAdd)
    if (updatedContent == null) {
        logger.error { "Could not find </content> closing tag in $imlPath" }
        return ImlUpdateResult(added = emptyList(), alreadyExcluded = alreadyExcluded, notApplied = toAdd)
    }

    SystemFileSystem.sink(imlPath).buffered().use { it.writeString(updatedContent) }
    logger.info { "Added ${toAdd.size} exclusion(s) to $imlPath: $toAdd" }

    return ImlUpdateResult(added = toAdd, alreadyExcluded = alreadyExcluded, notApplied = emptyList())
}

/**
 * Parses an IML file's XML content and returns the set of `url` attribute values
 * from all `<excludeFolder>` elements.
 */
internal fun parseExcludedFolderUrls(xmlContent: String): Set<String> {
    val excluded = mutableSetOf<String>()
    val reader = xmlStreaming.newGenericReader(xmlContent)

    try {
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT && reader.localName == EXCLUDE_FOLDER_TAG) {
                val url = reader.getAttributeValue(null, "url")
                if (url != null) {
                    excluded.add(url)
                }
            }
        }
    } finally {
        reader.close()
    }

    return excluded
}

/**
 * Inserts new `<excludeFolder>` entries into the raw XML text before `</content>`.
 * Returns `null` if no `</content>` tag is found.
 */
internal fun insertExcludeFolders(xmlContent: String, directories: List<String>): String? {
    val contentCloseRegex = Regex("""\s*</content>""")
    val match = contentCloseRegex.find(xmlContent) ?: return null

    val indentation = detectExcludeFolderIndentation(xmlContent) ?: "      "

    val newEntries = directories.joinToString("\n") { dir ->
        """$indentation<excludeFolder url="$MODULE_DIR_PREFIX$dir" />"""
    }

    return buildString(xmlContent.length + newEntries.length + 1) {
        append(xmlContent, 0, match.range.first)
        append('\n')
        append(newEntries)
        append(xmlContent, match.range.first, xmlContent.length)
    }
}

private fun detectExcludeFolderIndentation(xmlContent: String): String? {
    val indentRegex = Regex("""^(\s*)<excludeFolder\s""", RegexOption.MULTILINE)
    return indentRegex.find(xmlContent)?.groupValues?.get(1)
}
