package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

internal actual fun validateEngHubConfigTransactionPaths(
    primaryPath: Path,
    pendingPath: Path,
    backupPath: Path,
) {
    val paths = listOf(primaryPath, pendingPath, backupPath)
    val attributes = paths.map(::readAttributesIfPresent)
    paths.zip(attributes).forEach { (path, pathAttributes) ->
        if (pathAttributes != null) {
            require(pathAttributes.isRegularFile) {
                "Eng Hub configuration transaction destinations must be regular files: $path"
            }
        }
    }
    val identities = paths.map(::resolvedPathIdentity)
    require(identities.distinct().size == identities.size && !existingPathsAlias(paths, attributes)) {
        "Eng Hub configuration transaction paths must be distinct"
    }
}

internal actual fun replaceEngHubConfigPendingFile(path: Path, content: String) {
    val nioPath = java.nio.file.Path.of(path.toString())
    removeRegularCrashResidue(nioPath)
    var created = false
    runCatching {
        Files.newByteChannel(nioPath, setOf(CREATE_NEW, WRITE)).use { channel ->
            created = true
            channel.writeAll(content.encodeToByteArray())
        }
    }.getOrElse { error ->
        cleanupFailedCreation(nioPath, created)
        throw error
    }
}

private fun removeRegularCrashResidue(path: java.nio.file.Path) {
    val attributes = readAttributesIfPresent(Path(path.toString())) ?: return
    require(attributes.isRegularFile) {
        "Pending Eng Hub configuration destination must be a regular file: $path"
    }
    Files.delete(path)
}

private fun cleanupFailedCreation(path: java.nio.file.Path, created: Boolean) {
    if (!created) return
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        // The creation failure is the actionable error.
    } catch (_: SecurityException) {
        // The creation failure is the actionable error.
    }
}

private fun existingPathsAlias(
    paths: List<Path>,
    attributes: List<BasicFileAttributes?>,
): Boolean = paths.indices.any { leftIndex ->
    ((leftIndex + 1)..paths.lastIndex).any { rightIndex ->
        attributes[leftIndex] != null &&
            attributes[rightIndex] != null &&
            Files.isSameFile(
                java.nio.file.Path.of(paths[leftIndex].toString()),
                java.nio.file.Path.of(paths[rightIndex].toString()),
            )
    }
}

private fun readAttributesIfPresent(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(
        java.nio.file.Path.of(path.toString()),
        BasicFileAttributes::class.java,
        NOFOLLOW_LINKS,
    )
} catch (_: NoSuchFileException) {
    null
}

private fun resolvedPathIdentity(path: Path): String {
    val absolutePath = java.nio.file.Path.of(path.toString()).toAbsolutePath().normalize()
    var existingAncestor: java.nio.file.Path? = absolutePath
    while (existingAncestor != null && !Files.exists(existingAncestor, NOFOLLOW_LINKS)) {
        existingAncestor = existingAncestor.parent
    }
    if (existingAncestor == null) return absolutePath.toString().lowercase()

    val resolvedAncestor = existingAncestor.toRealPath()
    val unresolvedSuffix = existingAncestor.relativize(absolutePath)
    return resolvedAncestor.resolve(unresolvedSuffix).normalize().toString().lowercase()
}

private fun SeekableByteChannel.writeAll(content: ByteArray) {
    val buffer = ByteBuffer.wrap(content)
    while (buffer.hasRemaining()) write(buffer)
}
