package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GitHubConfigStoreSecurityTest {
    @Test
    fun permissionFailureDuringConfigPromotionIsWrappedAndRestoresPreviousPrimary() {
        val configPath = Path(SystemTemporaryDirectory, "github-config-security-${Random.nextLong()}.json")
        val pendingPath = Path("$configPath.new")
        val backupPath = Path("$configPath.bak")
        val original = GitHubConfig("/secrets/original.json")
        val replacement = GitHubConfig("/secrets/replacement.json")
        GitHubConfigStore().saveConfig(configPath, original)
        val permissionFailure = SecurityException("promotion denied")
        val failingMove: (Path, Path) -> Unit = { source, destination ->
            if (source == pendingPath && destination == configPath) throw permissionFailure
            SystemFileSystem.atomicMove(source, destination)
        }

        try {
            val error = assertFailsWith<GitHubConfigWriteException> {
                GitHubConfigStore(moveFile = failingMove).saveConfig(configPath, replacement)
            }

            assertEquals(permissionFailure, error.cause)
            assertEquals(original, decodeConfig(configPath))
            assertFalse(SystemFileSystem.exists(pendingPath))
        } finally {
            listOf(pendingPath, configPath, backupPath).forEach {
                SystemFileSystem.delete(it, mustExist = false)
            }
        }
    }

    private fun decodeConfig(path: Path): GitHubConfig {
        val source = SystemFileSystem.source(path).buffered()
        return source.use { lenientJson.decodeFromString(it.readString()) }
    }
}
