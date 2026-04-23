package com.github.karlsabo.notifications

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

internal fun createNotificationStoreTestDir(): Path {
    val path = Path(
        SystemTemporaryDirectory,
        "notification-store-test-${Random.nextLong().toULong().toString(16)}",
    )
    SystemFileSystem.createDirectories(path)
    return path
}

internal fun deleteRecursively(path: Path) {
    if (!SystemFileSystem.exists(path)) return
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    SystemFileSystem.delete(path, mustExist = false)
}
