package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.fstat
import platform.posix.link
import platform.posix.stat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeGitHubSecretFileWriterTest {
    @Test
    fun ownerOnlyFileIsEmptyAndRestrictedBeforeSecretBytesAreWritten() {
        val directory = Path(SystemTemporaryDirectory, "native-github-secret-${Random.nextLong()}")
        val secretPath = Path(directory, "github-secret.json.new")
        SystemFileSystem.createDirectories(directory)

        val descriptor = createOwnerOnlySecretFile(secretPath)
        try {
            memScoped {
                val fileStatus = alloc<stat>()
                assertEquals(0, fstat(descriptor, fileStatus.ptr))
                assertEquals(S_IRUSR or S_IWUSR, fileStatus.st_mode.toInt() and PERMISSION_MASK)
                assertEquals(0, fileStatus.st_size)
            }
            assertTrue(SystemFileSystem.exists(secretPath))
        } finally {
            close(descriptor)
            SystemFileSystem.delete(secretPath, mustExist = false)
            SystemFileSystem.delete(directory, mustExist = false)
        }
    }

    @Test
    fun rejectsMultiplyLinkedPrimaryWithoutRotatingOrReplacingIt() {
        val directory = Path(SystemTemporaryDirectory, "native-github-secret-${Random.nextLong()}")
        val secretPath = Path(directory, "github-secret.json")
        val aliasPath = Path(directory, "secret-alias.json")
        val pendingPath = Path("$secretPath.new")
        val backupPath = Path("$secretPath.bak")
        val originalSecret = lenientJson.encodeToString(GitHubSecret.serializer(), GitHubSecret("old-token"))
        val writer = createGitHubSecretFileWriter()
        SystemFileSystem.createDirectories(directory)
        writer.write(secretPath, originalSecret)
        assertEquals(0, link(secretPath.toString(), aliasPath.toString()))

        try {
            assertFailsWith<GitHubSecretWriteException> {
                writer.write(
                    secretPath,
                    lenientJson.encodeToString(GitHubSecret.serializer(), GitHubSecret("new-token")),
                )
            }

            assertEquals(originalSecret, readFile(secretPath))
            assertEquals(originalSecret, readFile(aliasPath))
            assertFalse(SystemFileSystem.exists(pendingPath))
            assertFalse(SystemFileSystem.exists(backupPath))
        } finally {
            SystemFileSystem.delete(aliasPath, mustExist = false)
            SystemFileSystem.delete(secretPath, mustExist = false)
            SystemFileSystem.delete(pendingPath, mustExist = false)
            SystemFileSystem.delete(backupPath, mustExist = false)
            SystemFileSystem.delete(directory, mustExist = false)
        }
    }

    private fun readFile(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    private companion object {
        const val PERMISSION_MASK = 0b1_1111_1111
    }
}
