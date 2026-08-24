package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubSecretFileWriterTest {
    @Test
    fun rejectsDirectoryDestinationBeforeChangingItsPermissionsOrLocation() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val directory = Files.createTempDirectory("github-secret-writer-")
        val secretPath = directory.resolve("github-secret.json")
        val marker = secretPath.resolve("keep.txt")
        Files.createDirectory(secretPath)
        Files.writeString(marker, "keep")
        val originalPermissions = Files.getPosixFilePermissions(secretPath)

        try {
            assertFailsWith<GitHubSecretWriteException> {
                GitHubConfigStore().save(Path(secretPath.toString()), GitHubSecret("github_pat_new"))
            }

            assertEquals(originalPermissions, Files.getPosixFilePermissions(secretPath))
            assertEquals("keep", Files.readString(marker))
            assertTrue(Files.isDirectory(secretPath))
            assertFalse(Files.exists(directory.resolve("github-secret.json.new")))
            assertFalse(Files.exists(directory.resolve("github-secret.json.bak")))
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsSymbolicLinkDestinationWithoutChangingItsTarget() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val directory = Files.createTempDirectory("github-secret-writer-")
        val targetPath = directory.resolve("target.json")
        val secretPath = directory.resolve("github-secret.json")
        Files.writeString(targetPath, "target contents")
        Files.createSymbolicLink(secretPath, targetPath)
        val originalPermissions = Files.getPosixFilePermissions(targetPath)

        try {
            assertFailsWith<GitHubSecretWriteException> {
                GitHubConfigStore().save(Path(secretPath.toString()), GitHubSecret("github_pat_new"))
            }

            assertTrue(Files.isSymbolicLink(secretPath))
            assertEquals("target contents", Files.readString(targetPath))
            assertEquals(originalPermissions, Files.getPosixFilePermissions(targetPath))
            assertFalse(Files.exists(directory.resolve("github-secret.json.new")))
            assertFalse(Files.exists(directory.resolve("github-secret.json.bak")))
        } finally {
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(targetPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsMultiplyLinkedPrimaryWithoutRotatingOrReplacingIt() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
        val directory = Files.createTempDirectory("github-secret-writer-")
        val secretPath = directory.resolve("github-secret.json")
        val aliasPath = directory.resolve("secret-alias.json")
        val originalSecret = lenientJson.encodeToString(GitHubSecret("old-token"))
        Files.writeString(secretPath, originalSecret)
        Files.createLink(aliasPath, secretPath)

        try {
            assertFailsWith<GitHubSecretWriteException> {
                GitHubConfigStore().save(Path(secretPath.toString()), GitHubSecret("new-token"))
            }

            assertEquals(originalSecret, Files.readString(secretPath))
            assertEquals(originalSecret, Files.readString(aliasPath))
            assertTrue(Files.isSameFile(secretPath, aliasPath))
            assertFalse(Files.exists(directory.resolve("github-secret.json.new")))
            assertFalse(Files.exists(directory.resolve("github-secret.json.bak")))
        } finally {
            Files.deleteIfExists(aliasPath)
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsSecretPathThroughSymlinkedParentBeforeAnyWrite() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val directory = Files.createTempDirectory("github-path-alias-")
        val configPath = directory.resolve("github-config.json")
        val aliasDirectory = directory.resolve("alias")
        val originalConfig = lenientJson.encodeToString(GitHubConfig("/secrets/original.json"))
        Files.writeString(configPath, originalConfig)
        Files.createSymbolicLink(aliasDirectory, directory)
        var secretWrites = 0
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { _, _ -> secretWrites++ },
        )

        try {
            assertFailsWith<GitHubSecretWriteException> {
                store.saveAccess(
                    Path(configPath.toString()),
                    Path(aliasDirectory.resolve("github-config.json").toString()),
                    GitHubSecret("github_pat_new"),
                )
            }

            assertEquals(0, secretWrites)
            assertEquals(originalConfig, Files.readString(configPath))
            assertFalse(Files.exists(directory.resolve("github-config.json.new")))
            assertFalse(Files.exists(directory.resolve("github-config.json.bak")))
        } finally {
            Files.deleteIfExists(aliasDirectory)
            Files.deleteIfExists(configPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsProtectedPathThroughSymlinkedParentBeforeAnyWrite() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val directory = Files.createTempDirectory("github-protected-path-alias-")
        val configPath = directory.resolve("github-config.json")
        val secretPath = directory.resolve("github-secret.json")
        val protectedPath = directory.resolve("eng-hub-config.json")
        val aliasDirectory = directory.resolve("alias")
        Files.writeString(configPath, lenientJson.encodeToString(GitHubConfig("/secrets/original.json")))
        Files.writeString(protectedPath, "protected contents")
        Files.createSymbolicLink(aliasDirectory, directory)
        var secretWrites = 0
        val store = GitHubConfigStore(
            secretFileWriter = GitHubSecretFileWriter { _, _ -> secretWrites++ },
        )
        val protectedPaths = listOf(
            Path(protectedPath.toString()),
            Path("$protectedPath.new"),
            Path("$protectedPath.bak"),
        )

        try {
            assertFailsWith<GitHubSecretWriteException> {
                store.saveAccess(
                    Path(configPath.toString()),
                    Path(aliasDirectory.resolve("eng-hub-config.json").toString()),
                    GitHubSecret("github_pat_new"),
                    protectedPaths,
                )
            }

            assertEquals(0, secretWrites)
            assertEquals("protected contents", Files.readString(protectedPath))
            assertFalse(Files.exists(secretPath))
        } finally {
            Files.deleteIfExists(aliasDirectory)
            Files.deleteIfExists(protectedPath)
            Files.deleteIfExists(configPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun createsSecretWithOwnerReadWritePermissionsOnlyOnPosix() = runBlocking {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val directory = Files.createTempDirectory("github-secret-writer-")
        val secretPath = directory.resolve("github-secret.json")

        try {
            GitHubConfigStore().save(Path(secretPath.toString()), GitHubSecret("github_pat_new"))

            assertEquals(setOf(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(secretPath))
        } finally {
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun aclFallbackRestrictsAccessToTheOwner() {
        val supportedViews = FileSystems.getDefault().supportedFileAttributeViews()
        if ("acl" !in supportedViews || "posix" in supportedViews) return
        val secretPath = Files.createTempFile("github-secret-writer-", ".json")

        try {
            val view = Files.getFileAttributeView(secretPath, AclFileAttributeView::class.java)
            val permissions = createJvmSecretFilePermissions(setOf("acl"))

            permissions.restrict(secretPath)
            permissions.verify(secretPath)

            val acl = view.acl
            assertTrue(acl.isNotEmpty())
            assertTrue(acl.all { entry -> entry.type() == ALLOW && entry.principal() == view.owner })
            assertEquals(EnumSet.allOf(AclEntryPermission::class.java), acl.flatMap { it.permissions() }.toSet())
        } finally {
            Files.deleteIfExists(secretPath)
        }
    }

    @Test
    fun aclFallbackRejectsAnAclWithoutOwnerReadAccess() {
        val supportedViews = FileSystems.getDefault().supportedFileAttributeViews()
        if ("acl" !in supportedViews || "posix" in supportedViews) return
        val secretPath = Files.createTempFile("github-secret-writer-", ".json")

        try {
            val view = Files.getFileAttributeView(secretPath, AclFileAttributeView::class.java)
            val insufficientPermissions = EnumSet.allOf(AclEntryPermission::class.java).apply {
                remove(AclEntryPermission.READ_DATA)
            }
            view.acl = listOf(
                AclEntry.newBuilder()
                    .setType(ALLOW)
                    .setPrincipal(view.owner)
                    .setPermissions(insufficientPermissions)
                    .build(),
            )

            assertFailsWith<GitHubSecretWriteException> {
                createJvmSecretFilePermissions(setOf("acl")).verify(secretPath)
            }
        } finally {
            Files.deleteIfExists(secretPath)
        }
    }

    @Test
    fun rejectsFileSystemsWithoutSupportedPermissionModel() {
        assertFailsWith<GitHubSecretWriteException> {
            createJvmSecretFilePermissions(emptySet())
        }
    }

    @Test
    fun replacingSecretRotatesTheOldContentsToBackup() = runBlocking {
        val directory = Files.createTempDirectory("github-secret-writer-")
        val secretPath = directory.resolve("github-secret.json")
        val backupPath = directory.resolve("github-secret.json.bak")
        val store = GitHubConfigStore()
        try {
            store.save(Path(secretPath.toString()), GitHubSecret("old-token"))
            store.save(Path(secretPath.toString()), GitHubSecret("new-token"))

            assertEquals(GitHubSecret("new-token"), decodeSecret(secretPath))
            assertEquals(GitHubSecret("old-token"), decodeSecret(backupPath))
            assertFalse(Files.exists(directory.resolve("github-secret.json.new")))
        } finally {
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(backupPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun failedSecretPromotionRestoresTheOldContents() = runBlocking {
        val directory = Files.createTempDirectory("github-secret-writer-")
        val secretPath = directory.resolve("github-secret.json")
        val pendingPath = directory.resolve("github-secret.json.new")
        val backupPath = directory.resolve("github-secret.json.bak")
        val initialStore = GitHubConfigStore()
        initialStore.save(Path(secretPath.toString()), GitHubSecret("old-token"))
        val failingWriter = JvmGitHubSecretFileWriter { source, destination ->
            if (source == pendingPath && destination == secretPath) throw IOException("promotion failed")
            Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        try {
            assertFailsWith<GitHubSecretWriteException> {
                GitHubConfigStore(secretFileWriter = failingWriter).save(
                    Path(secretPath.toString()),
                    GitHubSecret("new-token"),
                )
            }

            assertEquals(GitHubSecret("old-token"), decodeSecret(secretPath))
            assertFalse(Files.exists(pendingPath))
        } finally {
            Files.deleteIfExists(secretPath)
            Files.deleteIfExists(pendingPath)
            Files.deleteIfExists(backupPath)
            Files.deleteIfExists(directory)
        }
    }

    private fun decodeSecret(path: java.nio.file.Path): GitHubSecret {
        val encodedSecret = Files.readString(path)
        return lenientJson.decodeFromString(encodedSecret)
    }
}
