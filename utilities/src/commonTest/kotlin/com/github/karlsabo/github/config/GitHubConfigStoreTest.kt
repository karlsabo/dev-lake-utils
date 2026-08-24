package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubConfigStoreTest {
    @Test
    fun savesTokenOnlyInReferencedSecretJson() = runBlocking {
        val paths = temporaryPaths()
        try {
            GitHubConfigStore().saveAccess(paths.config, paths.secret, GitHubSecret("github_pat_new"))

            val configJson = readText(paths.config)
            val secretJson = readText(paths.secret)
            assertEquals(GitHubConfig(paths.secret.toString()), lenientJson.decodeFromString(configJson))
            assertEquals(GitHubSecret("github_pat_new"), lenientJson.decodeFromString(secretJson))
            assertFalse("github_pat_new" in configJson)
        } finally {
            paths.delete()
        }
    }

    @Test
    fun rejectsNormalizedAliasesOfEveryConfigTransactionPathBeforeWriting() = runBlocking {
        val paths = temporaryPaths()
        val originalConfig = GitHubConfig("/secrets/original.json")
        writeText(paths.config, lenientJson.encodeToString(originalConfig))
        val writes = mutableListOf<Path>()
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { path, _ -> writes += path },
        )
        val aliases = listOf(
            paths.config,
            Path("${paths.config}.new"),
            Path("${paths.config}.bak"),
            Path(paths.directory, "unused/../github-config.json"),
        )

        try {
            aliases.forEach { alias ->
                assertFailsWith<GitHubSecretWriteException> {
                    store.saveAccess(paths.config, alias, GitHubSecret("github_pat_new"))
                }
            }

            assertTrue(writes.isEmpty())
            assertEquals(originalConfig, decodeConfig(paths.config))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.new")))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.bak")))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun rejectsCaseVariantsOfExistingAndAbsentConfigTransactionPathsBeforeWriting() = runBlocking {
        val paths = temporaryPaths()
        val originalConfig = GitHubConfig("/secrets/original.json")
        writeText(paths.config, lenientJson.encodeToString(originalConfig))
        val writes = mutableListOf<Path>()
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { path, _ -> writes += path },
        )
        val aliases = listOf(
            Path(paths.directory, "GITHUB-CONFIG.JSON"),
            Path(paths.directory, "GITHUB-CONFIG.JSON.NEW"),
            Path(paths.directory, "GITHUB-CONFIG.JSON.BAK"),
        )

        try {
            aliases.forEach { alias ->
                assertFailsWith<GitHubSecretWriteException> {
                    store.saveAccess(paths.config, alias, GitHubSecret("github_pat_new"))
                }
            }

            assertTrue(writes.isEmpty())
            assertEquals(originalConfig, decodeConfig(paths.config))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.new")))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.bak")))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun rejectsProtectedTransactionAliasesBeforeAnySecretOrConfigWrite() = runBlocking {
        val paths = temporaryPaths()
        val protectedPrimary = Path(paths.directory, "eng-hub-config.json")
        val originalConfig = GitHubConfig("/secrets/original.json")
        writeText(paths.config, lenientJson.encodeToString(originalConfig))
        writeText(protectedPrimary, "protected primary")
        writeText(Path("$protectedPrimary.bak"), "protected backup")
        val writes = mutableListOf<Path>()
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { path, _ -> writes += path },
        )
        val protectedPaths = listOf(
            protectedPrimary,
            Path("$protectedPrimary.new"),
            Path("$protectedPrimary.bak"),
        )
        val aliases = listOf(
            protectedPrimary,
            Path("$protectedPrimary.new"),
            Path("$protectedPrimary.bak"),
            Path(paths.directory, "unused/../eng-hub-config.json"),
            Path(paths.directory, "ENG-HUB-CONFIG.JSON.BAK"),
        )

        try {
            aliases.forEach { alias ->
                assertFailsWith<GitHubSecretWriteException> {
                    store.saveAccess(
                        paths.config,
                        alias,
                        GitHubSecret("github_pat_new"),
                        protectedPaths,
                    )
                }
            }

            assertTrue(writes.isEmpty())
            assertEquals(originalConfig, decodeConfig(paths.config))
            assertEquals("protected primary", readText(protectedPrimary))
            assertEquals("protected backup", readText(Path("$protectedPrimary.bak")))
            assertFalse(SystemFileSystem.exists(Path("$protectedPrimary.new")))
        } finally {
            listOf(Path("$protectedPrimary.new"), protectedPrimary, Path("$protectedPrimary.bak"))
                .forEach { SystemFileSystem.delete(it, mustExist = false) }
            paths.delete()
        }
    }

    @Test
    fun rejectsConfigurationThatAliasesAProtectedTransactionBeforeWriting() = runBlocking {
        val paths = temporaryPaths()
        val protectedPrimary = Path(paths.directory, "eng-hub-config.json")
        val protectedPaths = listOf(
            protectedPrimary,
            Path("$protectedPrimary.new"),
            Path("$protectedPrimary.bak"),
        )
        val writes = mutableListOf<Path>()
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { path, _ -> writes += path },
        )

        try {
            assertFailsWith<GitHubSecretWriteException> {
                store.saveAccess(
                    Path(paths.directory, "unused/../eng-hub-config.json"),
                    paths.secret,
                    GitHubSecret("github_pat_new"),
                    protectedPaths,
                )
            }

            assertTrue(writes.isEmpty())
            assertFalse(SystemFileSystem.exists(paths.secret))
            assertFalse(SystemFileSystem.exists(protectedPrimary))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun rejectsDirectoryAtConfigPendingPathBeforeWritingSecretOrConfig() = runBlocking {
        val paths = temporaryPaths()
        val pendingPath = Path("${paths.config}.new")
        val writes = mutableListOf<Path>()
        SystemFileSystem.createDirectories(pendingPath)
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { path, _ -> writes += path },
        )

        try {
            assertFailsWith<GitHubSecretWriteException> {
                store.saveAccess(paths.config, paths.secret, GitHubSecret("github_pat_new"))
            }

            assertTrue(writes.isEmpty())
            assertTrue(SystemFileSystem.metadataOrNull(pendingPath)?.isDirectory == true)
            assertFalse(SystemFileSystem.exists(paths.config))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun rejectsADirectorySecretDestinationWithoutMutatingIt() = runBlocking {
        val paths = temporaryPaths()
        val marker = Path(paths.secret, "keep.txt")
        SystemFileSystem.createDirectories(paths.secret)
        writeText(marker, "keep")

        try {
            assertFailsWith<GitHubSecretWriteException> {
                GitHubConfigStore().save(paths.secret, GitHubSecret("github_pat_new"))
            }

            assertEquals("keep", readText(marker))
            assertTrue(SystemFileSystem.exists(paths.secret))
            assertFalse(SystemFileSystem.exists(Path("${paths.secret}.new")))
            assertFalse(SystemFileSystem.exists(Path("${paths.secret}.bak")))
        } finally {
            SystemFileSystem.delete(marker, mustExist = false)
            paths.delete()
        }
    }

    @Test
    fun doesNotCreateConfigWhenSecretCreationFails() = runBlocking {
        val paths = temporaryPaths()
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { _, _ ->
                throw GitHubSecretWriteException("secret write failed")
            },
        )

        try {
            assertFailsWith<GitHubSecretWriteException> {
                store.saveAccess(paths.config, paths.secret, GitHubSecret("github_pat_new"))
            }
            assertFalse(SystemFileSystem.exists(paths.config))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun replacingConfigRotatesTheVerifiedPrimaryToBackup() {
        val paths = temporaryPaths()
        val original = GitHubConfig("/secrets/original.json")
        val replacement = GitHubConfig("/secrets/replacement.json")
        val store = GitHubConfigStore()
        try {
            store.saveConfig(paths.config, original)
            store.saveConfig(paths.config, replacement)

            assertEquals(replacement, decodeConfig(paths.config))
            assertEquals(original, decodeConfig(Path("${paths.config}.bak")))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.new")))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun replacingAnInvalidPrimaryDoesNotOverwriteAValidBackup() {
        val paths = temporaryPaths()
        val backup = GitHubConfig("/secrets/backup.json")
        val replacement = GitHubConfig("/secrets/replacement.json")
        writeText(paths.config, "invalid")
        writeText(Path("${paths.config}.bak"), lenientJson.encodeToString(backup))
        try {
            GitHubConfigStore().saveConfig(paths.config, replacement)

            assertEquals(replacement, decodeConfig(paths.config))
            assertEquals(backup, decodeConfig(Path("${paths.config}.bak")))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun failedConfigPromotionRestoresThePreviousPrimary() {
        val paths = temporaryPaths()
        val original = GitHubConfig("/secrets/original.json")
        val replacement = GitHubConfig("/secrets/replacement.json")
        GitHubConfigStore().saveConfig(paths.config, original)
        val pendingPath = Path("${paths.config}.new")
        val failingMove: (Path, Path) -> Unit = { source, destination ->
            if (source == pendingPath && destination == paths.config) throw IOException("promotion failed")
            SystemFileSystem.atomicMove(source, destination)
        }

        try {
            assertFailsWith<GitHubConfigWriteException> {
                GitHubConfigStore(moveFile = failingMove).saveConfig(paths.config, replacement)
            }

            assertEquals(original, decodeConfig(paths.config))
            assertFalse(SystemFileSystem.exists(Path("${paths.config}.new")))
        } finally {
            paths.delete()
        }
    }

    @Test
    fun invalidPrimaryRecoversConfigurationFromBackup() {
        val paths = temporaryPaths()
        val backupConfig = GitHubConfig(paths.secret.toString())
        writeText(paths.config, "invalid config")
        writeText(Path("${paths.config}.bak"), lenientJson.encodeToString(backupConfig))
        writeText(paths.secret, lenientJson.encodeToString(GitHubSecret("backup-token")))

        try {
            val loaded = assertNotNull(loadGitHubSettingsIfPresent(paths.config))
            assertEquals(backupConfig, loaded.config)
            assertEquals(GitHubSecret("backup-token"), loaded.secret)
        } finally {
            paths.delete()
        }
    }

    @Test
    fun invalidSecretNeverLoadsItsBackup() {
        val paths = temporaryPaths()
        val config = GitHubConfig(paths.secret.toString())
        writeText(paths.config, lenientJson.encodeToString(config))
        writeText(paths.secret, "invalid secret")
        writeText(Path("${paths.secret}.bak"), lenientJson.encodeToString(GitHubSecret("backup-token")))

        try {
            val loaded = assertNotNull(loadGitHubSettingsIfPresent(paths.config))
            assertEquals(config, loaded.config)
            assertEquals(GitHubSecret(""), loaded.secret)
        } finally {
            paths.delete()
        }
    }

    private fun temporaryPaths(): GitHubTestPaths {
        val directory = Path(SystemTemporaryDirectory, "github-config-store-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        return GitHubTestPaths(
            directory = directory,
            config = Path(directory, "github-config.json"),
            secret = Path(directory, "github-secret.json"),
        )
    }

    private fun decodeConfig(path: Path): GitHubConfig = lenientJson.decodeFromString(readText(path))
}

private data class GitHubTestPaths(
    val directory: Path,
    val config: Path,
    val secret: Path,
) {
    fun delete() {
        listOf(
            Path("$config.new"),
            config,
            Path("$config.bak"),
            Path("$secret.new"),
            secret,
            Path("$secret.bak"),
        ).forEach { path -> SystemFileSystem.delete(path, mustExist = false) }
        SystemFileSystem.delete(directory, mustExist = false)
    }
}

private fun readText(path: Path): String {
    val source = SystemFileSystem.source(path).buffered()
    return source.use { it.readString() }
}

private fun writeText(path: Path, text: String) {
    SystemFileSystem.sink(path, false).buffered().use { sink -> sink.writeString(text) }
}
