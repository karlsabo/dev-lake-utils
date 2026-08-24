package com.github.karlsabo.github.config

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal actual fun platformValidateRegularFileIfPresent(path: Path) {
    val attributes = try {
        Files.readAttributes(
            java.nio.file.Path.of(path.toString()),
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
    } catch (_: NoSuchFileException) {
        return
    } catch (error: java.io.IOException) {
        throw IllegalArgumentException("Could not inspect file path", error)
    } catch (error: SecurityException) {
        throw IllegalArgumentException("Could not inspect file path", error)
    }
    require(attributes.isRegularFile) { "Transaction destination must be a regular file: $path" }
}

internal actual fun platformResolvedFilePath(path: Path): String = try {
    resolvePathIdentity(path)
} catch (error: SecurityException) {
    throw IllegalArgumentException("Could not inspect file path", error)
}

private fun resolvePathIdentity(path: Path): String {
    val absolutePath = java.nio.file.Path.of(path.toString()).toAbsolutePath().normalize()
    var existingAncestor: java.nio.file.Path? = absolutePath
    while (existingAncestor != null && !Files.exists(existingAncestor)) {
        existingAncestor = existingAncestor.parent
    }
    if (existingAncestor == null) return absolutePath.toString()

    val resolvedAncestor = existingAncestor.toRealPath()
    val unresolvedSuffix = existingAncestor.relativize(absolutePath)
    return resolvedAncestor.resolve(unresolvedSuffix).normalize().toString()
}
