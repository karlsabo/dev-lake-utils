package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.files.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngHubConfigTransactionFilesTest {
    @Test
    fun pendingSymlinkToUnrelatedFileIsRejectedWithoutChangingEitherFile() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        val unrelated = paths.directory.resolve("unrelated.json")
        Files.writeString(paths.primary, primaryContent)
        Files.writeString(unrelated, "unrelated content")
        Files.createSymbolicLink(paths.pending, unrelated)

        assertWriteRejected(paths)

        assertEquals(primaryContent, Files.readString(paths.primary))
        assertEquals("unrelated content", Files.readString(unrelated))
        assertTrue(Files.isSymbolicLink(paths.pending))
    }

    @Test
    fun pendingSymlinkToPrimaryIsRejectedWithoutChangingPrimary() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        Files.writeString(paths.primary, primaryContent)
        Files.createSymbolicLink(paths.pending, paths.primary)

        assertWriteRejected(paths)

        assertEquals(primaryContent, Files.readString(paths.primary))
        assertTrue(Files.isSymbolicLink(paths.pending))
    }

    @Test
    fun primarySymlinkIsRejectedWithoutChangingItsTargetOrBackup() = withTransactionPaths { paths ->
        val target = paths.directory.resolve("target.json")
        Files.writeString(target, "unrelated content")
        Files.createSymbolicLink(paths.primary, target)
        Files.writeString(paths.backup, "backup content")

        assertWriteRejected(paths)

        assertEquals("unrelated content", Files.readString(target))
        assertEquals("backup content", Files.readString(paths.backup))
        assertTrue(Files.isSymbolicLink(paths.primary))
        assertFalse(Files.exists(paths.pending))
    }

    @Test
    fun backupSymlinkIsRejectedWithoutChangingAnyFile() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        val target = paths.directory.resolve("target.json")
        Files.writeString(paths.primary, primaryContent)
        Files.writeString(target, "unrelated content")
        Files.createSymbolicLink(paths.backup, target)

        assertWriteRejected(paths)

        assertEquals(primaryContent, Files.readString(paths.primary))
        assertEquals("unrelated content", Files.readString(target))
        assertTrue(Files.isSymbolicLink(paths.backup))
        assertFalse(Files.exists(paths.pending))
    }

    @Test
    fun hardLinkedPrimaryAndBackupAreRejectedWithoutChangingTheirSharedFile() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        Files.writeString(paths.primary, primaryContent)
        Files.createLink(paths.backup, paths.primary)

        assertWriteRejected(paths)

        assertEquals(primaryContent, Files.readString(paths.primary))
        assertEquals(primaryContent, Files.readString(paths.backup))
        assertFalse(Files.exists(paths.pending))
    }

    @Test
    fun hardLinkedPendingAndPrimaryAreRejectedWithoutChangingTheirSharedFile() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        Files.writeString(paths.primary, primaryContent)
        Files.createLink(paths.pending, paths.primary)

        assertWriteRejected(paths)

        assertEquals(primaryContent, Files.readString(paths.primary))
        assertEquals(primaryContent, Files.readString(paths.pending))
        assertTrue(Files.isSameFile(paths.primary, paths.pending))
    }

    @Test
    fun directoryAtAnyTransactionPathIsRejectedBeforeMutation() {
        TransactionPath.entries.forEach { directoryPath ->
            withTransactionPaths { paths ->
                val primaryContent = encodedConfig("octocat")
                if (directoryPath != TransactionPath.PRIMARY) Files.writeString(paths.primary, primaryContent)
                if (directoryPath != TransactionPath.BACKUP) Files.writeString(paths.backup, "backup content")
                val path = directoryPath.select(paths)
                if (Files.exists(path)) Files.delete(path)
                Files.createDirectory(path)
                Files.writeString(path.resolve("marker"), "unchanged")

                assertWriteRejected(paths)

                assertEquals("unchanged", Files.readString(path.resolve("marker")))
                if (directoryPath != TransactionPath.PRIMARY) {
                    assertEquals(primaryContent, Files.readString(paths.primary))
                }
                if (directoryPath != TransactionPath.PENDING) assertFalse(Files.exists(paths.pending))
                if (directoryPath != TransactionPath.BACKUP) {
                    assertEquals("backup content", Files.readString(paths.backup))
                }
            }
        }
    }

    @Test
    fun existingRegularPendingFileIsReplacedAndSaveCompletes() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        Files.writeString(paths.primary, primaryContent)
        Files.writeString(paths.pending, "stale pending content")

        saveEngHubConfig(EngHubConfig(gitHubAuthor = "hubot"), Path(paths.primary.toString()))

        assertEquals(encodedConfig("hubot"), Files.readString(paths.primary))
        assertEquals(primaryContent, Files.readString(paths.backup))
        assertFalse(Files.exists(paths.pending))
    }

    @Test
    fun unixSocketAtPendingPathIsRejectedWithoutChangingPrimary() = withTransactionPaths { paths ->
        val primaryContent = encodedConfig("octocat")
        Files.writeString(paths.primary, primaryContent)
        val server = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                bind(UnixDomainSocketAddress.of(paths.pending))
            }
        } catch (_: UnsupportedOperationException) {
            return@withTransactionPaths
        }

        server.use {
            assertWriteRejected(paths)
            assertEquals(primaryContent, Files.readString(paths.primary))
            assertTrue(Files.exists(paths.pending))
        }
    }

    private fun assertWriteRejected(paths: TransactionPaths) {
        assertFailsWith<EngHubConfigWriteException> {
            saveEngHubConfig(EngHubConfig(gitHubAuthor = "hubot"), Path(paths.primary.toString()))
        }
    }

    private fun encodedConfig(author: String): String = lenientJson.encodeToString(
        EngHubConfig.serializer(),
        EngHubConfig(gitHubAuthor = author),
    )

    private fun withTransactionPaths(test: (TransactionPaths) -> Unit) {
        val directory = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "eh-config-")
        try {
            test(TransactionPaths(directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class TransactionPaths(
        val directory: java.nio.file.Path,
    ) {
        val primary: java.nio.file.Path = directory.resolve("eng-hub-config.json")
        val pending: java.nio.file.Path = directory.resolve("eng-hub-config.json.new")
        val backup: java.nio.file.Path = directory.resolve("eng-hub-config.json.bak")
    }

    private enum class TransactionPath {
        PRIMARY,
        PENDING,
        BACKUP,
        ;

        fun select(paths: TransactionPaths): java.nio.file.Path = when (this) {
            PRIMARY -> paths.primary
            PENDING -> paths.pending
            BACKUP -> paths.backup
        }
    }
}
