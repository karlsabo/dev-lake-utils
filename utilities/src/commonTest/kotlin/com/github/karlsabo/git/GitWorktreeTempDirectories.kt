package com.github.karlsabo.git

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

internal fun createArchiveWorktreeTempDir(): String {
    val dirName = "repo-feature-${Random.nextLong().toULong().toString(16)}"
    val path = Path(SystemTemporaryDirectory, dirName)
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

internal fun removeTempDir(path: String) {
    val root = Path(path)
    if (!SystemFileSystem.exists(root)) return
    deleteRecursively(root)
}

private fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}
