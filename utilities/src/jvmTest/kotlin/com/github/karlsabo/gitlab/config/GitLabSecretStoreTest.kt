package com.github.karlsabo.gitlab.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.files.Path
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabSecretStoreTest {
    @Test
    fun writesTheTokenOnlyToItsProtectedSecretFile() {
        val directory = Files.createTempDirectory("gitlab-secret-store-")
        val secretPath = directory.resolve("gitlab-secret.json")
        val token = "glpat-synthetic-secret"

        try {
            GitLabSecretStore().save(Path(secretPath.toString()), GitLabSecret(token))

            val secretJson = Files.readString(secretPath)
            assertEquals(GitLabSecret(token), lenientJson.decodeFromString(secretJson))
            assertFalse(token in GitLabSecret(token).toString())

            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertEquals(setOf(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(secretPath))
            }
        } finally {
            delete(directory)
        }
    }

    @Test
    fun replacingTheSecretRotatesTheOldContentsToBackup() {
        val directory = Files.createTempDirectory("gitlab-secret-store-")
        val secretPath = directory.resolve("gitlab-secret.json")
        val store = GitLabSecretStore()

        try {
            store.save(Path(secretPath.toString()), GitLabSecret("old-token"))
            store.save(Path(secretPath.toString()), GitLabSecret("new-token"))

            assertEquals(GitLabSecret("new-token"), decodeSecret(secretPath))
            assertEquals(GitLabSecret("old-token"), decodeSecret(directory.resolve("gitlab-secret.json.bak")))
            assertFalse(Files.exists(directory.resolve("gitlab-secret.json.new")))
        } finally {
            delete(directory)
        }
    }

    @Test
    fun leavesThePreviousSecretInPlaceWhenReplacementFails() {
        val directory = Files.createTempDirectory("gitlab-secret-store-")
        val secretPath = directory.resolve("gitlab-secret.json")
        val failingStore = GitLabSecretStore(
            GitLabSecretFileWriter { _, _ -> throw GitLabSecretWriteException("write failed") },
        )

        try {
            GitLabSecretStore().save(Path(secretPath.toString()), GitLabSecret("old-token"))

            val error = runCatching {
                failingStore.save(Path(secretPath.toString()), GitLabSecret("new-token"))
            }.exceptionOrNull()

            assertTrue(error is GitLabSecretWriteException)
            assertEquals(GitLabSecret("old-token"), decodeSecret(secretPath))
        } finally {
            delete(directory)
        }
    }

    private fun decodeSecret(path: java.nio.file.Path): GitLabSecret {
        val encodedSecret = Files.readString(path)
        return lenientJson.decodeFromString(encodedSecret)
    }

    private fun delete(directory: java.nio.file.Path) {
        Files.list(directory).use { paths -> paths.forEach { path -> Files.deleteIfExists(path) } }
        Files.deleteIfExists(directory)
    }
}
