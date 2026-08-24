package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily

internal fun String.normalizedRepositoryPath(): String = normalizedRepositoryPath(osFamily())

internal fun String.normalizedRepositoryPath(family: OsFamily): String {
    val path = trim()
    if (path.isEmpty()) return ""

    val windows = family == OsFamily.WINDOWS
    val caseInsensitive = windows || family == OsFamily.MACOS
    val canonicalPath = if (windows) path.replace('\\', '/') else path
    val root = repositoryPathRoot(canonicalPath, windows)
    val segments = mutableListOf<String>()
    canonicalPath.substring(root.consumedCharacters).split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit

            segment == ".." && segments.isNotEmpty() && segments.last() != ".." -> {
                segments.removeAt(segments.lastIndex)
            }

            segment == ".." && !root.isAbsolute -> segments += segment

            segment != ".." -> segments += segment
        }
    }

    val normalized = root.render(segments)
    // Most macOS volumes are case-insensitive, and Settings paths need not exist yet, so macOS uses the
    // conservative identity. Linux remains case-sensitive; Windows remains case-insensitive by convention.
    return if (caseInsensitive) normalized.lowercase() else normalized
}

private data class RepositoryPathRoot(
    val prefix: String,
    val consumedCharacters: Int,
    val isAbsolute: Boolean,
    val joinsDirectly: Boolean = false,
) {
    fun render(segments: List<String>): String {
        val body = segments.joinToString("/")
        return when {
            body.isEmpty() && prefix.isNotEmpty() -> prefix
            body.isEmpty() -> "."
            prefix.isEmpty() -> body
            joinsDirectly -> prefix + body
            prefix.endsWith('/') -> prefix + body
            else -> "$prefix/$body"
        }
    }
}

private fun repositoryPathRoot(path: String, windows: Boolean): RepositoryPathRoot = when {
    windows && path.startsWith("//") -> {
        val rootSegments = path.dropWhile { it == '/' }.split('/').filter(String::isNotEmpty).take(2)
        RepositoryPathRoot(
            prefix = "//" + rootSegments.joinToString("/"),
            consumedCharacters = uncRootEnd(path, rootSegments.size),
            isAbsolute = true,
        )
    }

    windows && path.hasDrivePrefix() -> {
        val drive = path.substring(0, 2)
        val absolute = path.getOrNull(2) == '/'
        RepositoryPathRoot(
            prefix = if (absolute) "$drive/" else drive,
            consumedCharacters = if (absolute) path.indexAfterLeadingSeparators(2) else 2,
            isAbsolute = absolute,
            joinsDirectly = !absolute,
        )
    }

    path.startsWith('/') -> RepositoryPathRoot("/", path.indexAfterLeadingSeparators(), isAbsolute = true)

    else -> RepositoryPathRoot("", 0, isAbsolute = false)
}

private fun String.hasDrivePrefix(): Boolean = length >= 2 && this[0].isLetter() && this[1] == ':'

private fun String.indexAfterLeadingSeparators(startIndex: Int = 0): Int {
    var index = startIndex
    while (index < length && this[index] == '/') index += 1
    return index
}

private fun uncRootEnd(path: String, rootSegmentCount: Int): Int {
    var index = path.indexAfterLeadingSeparators()
    repeat(rootSegmentCount) {
        while (index < path.length && path[index] != '/') index += 1
        index = path.indexAfterLeadingSeparators(index)
    }
    return index
}
