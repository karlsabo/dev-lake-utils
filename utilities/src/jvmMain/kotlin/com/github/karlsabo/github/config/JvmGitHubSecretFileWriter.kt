package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal actual fun createGitHubSecretFileWriter(): GitHubSecretFileWriter = JvmGitHubSecretFileWriter()

internal class JvmGitHubSecretFileWriter(
    private val moveFile: (java.nio.file.Path, java.nio.file.Path) -> Unit = ::atomicReplace,
) : GitHubSecretFileWriter {
    override fun write(path: Path, encodedSecret: String) {
        val primaryPath = java.nio.file.Path.of(path.toString())
        val pendingPath = java.nio.file.Path.of("$path.new")
        val backupPath = java.nio.file.Path.of("$path.bak")
        validateSecretDestinations(path, primaryPath, pendingPath, backupPath)
        try {
            val filePermissions = createJvmSecretFilePermissions()
            Files.deleteIfExists(pendingPath)
            filePermissions.create(pendingPath, encodedSecret.encodeToByteArray())
            verifyPendingSecret(pendingPath, encodedSecret, filePermissions)
            promoteSecret(pendingPath, primaryPath, backupPath, filePermissions)
        } catch (error: GitHubSecretWriteException) {
            deleteBestEffort(pendingPath)
            throw error
        } catch (error: IOException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: SecurityException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: UnsupportedOperationException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: SerializationException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: IllegalArgumentException) {
            throwWriteException(path, pendingPath, error)
        }
    }

    private fun verifyPendingSecret(
        path: java.nio.file.Path,
        encodedSecret: String,
        filePermissions: JvmSecretFilePermissions,
    ) {
        val expected = lenientJson.decodeFromString<GitHubSecret>(encodedSecret)
        val actual = lenientJson.decodeFromString<GitHubSecret>(Files.readString(path))
        if (actual != expected) {
            throw GitHubSecretWriteException("Could not verify pending GitHub secret file")
        }
        filePermissions.verify(path)
    }

    private fun promoteSecret(
        pendingPath: java.nio.file.Path,
        primaryPath: java.nio.file.Path,
        backupPath: java.nio.file.Path,
        filePermissions: JvmSecretFilePermissions,
    ) {
        val rotatedPrimary = Files.exists(primaryPath)
        if (rotatedPrimary) {
            filePermissions.restrict(primaryPath)
            filePermissions.verify(primaryPath)
            moveFile(primaryPath, backupPath)
        }
        try {
            moveFile(pendingPath, primaryPath)
            filePermissions.verify(primaryPath)
        } catch (error: IOException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        } catch (error: SecurityException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        } catch (error: UnsupportedOperationException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        } catch (error: GitHubSecretWriteException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        }
    }

    private fun restoreAfterPromotionFailure(
        rotatedPrimary: Boolean,
        backupPath: java.nio.file.Path,
        primaryPath: java.nio.file.Path,
        error: Throwable,
    ): Nothing {
        if (rotatedPrimary) {
            restoreBackupBestEffort(backupPath, primaryPath)
        } else {
            deleteBestEffort(primaryPath)
        }
        throw error
    }

    private fun restoreBackupBestEffort(
        backupPath: java.nio.file.Path,
        primaryPath: java.nio.file.Path,
    ) {
        try {
            moveFile(backupPath, primaryPath)
        } catch (_: IOException) {
            // The promotion failure is the useful error to report.
        } catch (_: SecurityException) {
            // The promotion failure is the useful error to report.
        } catch (_: UnsupportedOperationException) {
            // The promotion failure is the useful error to report.
        }
    }

    private fun throwWriteException(
        path: Path,
        pendingPath: java.nio.file.Path,
        error: Throwable,
    ): Nothing {
        deleteBestEffort(pendingPath)
        throw GitHubSecretWriteException("Could not securely write GitHub secret file: $path", error)
    }

    private fun deleteBestEffort(path: java.nio.file.Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Cleanup must not hide the storage failure.
        } catch (_: SecurityException) {
            // Cleanup must not hide the storage failure.
        }
    }
}

private fun validateSecretDestinations(
    configuredPath: Path,
    primaryPath: java.nio.file.Path,
    pendingPath: java.nio.file.Path,
    backupPath: java.nio.file.Path,
) {
    listOf(primaryPath, pendingPath, backupPath).forEach { candidate ->
        val attributes = readSecretAttributes(configuredPath, candidate) ?: return@forEach
        if (!attributes.isRegularFile) {
            throw GitHubSecretWriteException("GitHub secret destination must be a regular file: $configuredPath")
        }
        if (candidate == primaryPath && hasMultipleHardLinks(configuredPath, candidate)) {
            throw GitHubSecretWriteException(
                "GitHub secret destination must not have multiple hard links: $configuredPath",
            )
        }
    }
}

private fun hasMultipleHardLinks(configuredPath: Path, candidate: java.nio.file.Path): Boolean {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) return false
    return try {
        (Files.getAttribute(candidate, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() > 1L
    } catch (error: IOException) {
        throwSecretInspectionFailure(configuredPath, error)
    } catch (error: SecurityException) {
        throwSecretInspectionFailure(configuredPath, error)
    }
}

private fun readSecretAttributes(
    configuredPath: Path,
    candidate: java.nio.file.Path,
): BasicFileAttributes? = try {
    Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
} catch (error: IOException) {
    throwSecretInspectionFailure(configuredPath, error)
} catch (error: SecurityException) {
    throwSecretInspectionFailure(configuredPath, error)
}

private fun throwSecretInspectionFailure(configuredPath: Path, error: Throwable): Nothing {
    val message = "Could not inspect GitHub secret destination: $configuredPath"
    throw GitHubSecretWriteException(message, error)
}

private fun atomicReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
}
