package com.github.karlsabo.github.config

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.free
import platform.posix.getcwd
import platform.posix.lstat
import platform.posix.realpath
import platform.posix.stat

internal actual fun platformValidateRegularFileIfPresent(path: Path) = memScoped {
    val fileStatus = alloc<stat>()
    when {
        lstat(path.toString(), fileStatus.ptr) == 0 -> {
            val fileType = fileStatus.st_mode.toInt() and S_IFMT
            require(fileType == S_IFREG) { "Transaction destination must be a regular file: $path" }
        }

        errno != ENOENT -> throw IllegalArgumentException("Could not inspect file path")
    }
}

internal actual fun platformResolvedFilePath(path: Path): String {
    val absolutePath = path.toAbsoluteNormalizedPath()
    var existingAncestor = absolutePath
    val suffix = mutableListOf<String>()
    while (true) {
        resolveRealPath(existingAncestor)?.let { resolved ->
            return (listOf(resolved.trimEnd('/')) + suffix.asReversed()).joinToString("/")
        }
        val separator = existingAncestor.lastIndexOf('/')
        if (separator <= 0) return absolutePath
        suffix += existingAncestor.substring(separator + 1)
        existingAncestor = existingAncestor.substring(0, separator).ifEmpty { "/" }
    }
}

private fun Path.toAbsoluteNormalizedPath(): String {
    val rawPath = toString()
    val absolutePath = if (isAbsolute) rawPath else "${currentDirectory()}/$rawPath"
    val components = mutableListOf<String>()
    absolutePath.split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
            else -> components += component
        }
    }
    return "/${components.joinToString("/")}"
}

private fun currentDirectory(): String {
    val buffer = getcwd(null, 0u) ?: return "/"
    return try {
        buffer.toKString()
    } finally {
        free(buffer)
    }
}

private fun resolveRealPath(path: String): String? {
    val buffer = realpath(path, null)
    if (buffer == null) {
        return when (errno) {
            ENOENT, ENOTDIR -> null
            else -> throw IllegalArgumentException("Could not inspect file path")
        }
    }
    return try {
        buffer.toKString()
    } finally {
        free(buffer)
    }
}
