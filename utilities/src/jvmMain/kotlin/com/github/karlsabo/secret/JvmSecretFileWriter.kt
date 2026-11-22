package com.github.karlsabo.secret

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal actual fun createSecretFileWriter(providerLabel: String): SecretFileWriter = JvmSecretFileWriter(providerLabel)

internal class JvmSecretFileWriter(
    private val providerLabel: String,
    private val moveFile: (java.nio.file.Path, java.nio.file.Path) -> Unit = ::atomicReplace,
) : SecretFileWriter {
    override fun write(path: Path, encodedSecret: String) {
        val primaryPath = java.nio.file.Path.of(path.toString())
        val pendingPath = java.nio.file.Path.of("$path.new")
        val backupPath = java.nio.file.Path.of("$path.bak")
        validateSecretDestinations(providerLabel, path, primaryPath, pendingPath, backupPath)
        try {
            val filePermissions = createJvmSecretFilePermissions(providerLabel = providerLabel)
            Files.deleteIfExists(pendingPath)
            filePermissions.create(pendingPath, encodedSecret.encodeToByteArray())
            verifyPendingSecret(pendingPath, encodedSecret, filePermissions)
            promoteSecret(pendingPath, primaryPath, backupPath, filePermissions)
        } catch (error: SecretFileWriteException) {
            deleteBestEffort(pendingPath)
            throw error
        } catch (error: IOException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        } catch (error: SecurityException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        } catch (error: UnsupportedOperationException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        } catch (error: IllegalArgumentException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        }
    }

    private fun verifyPendingSecret(
        path: java.nio.file.Path,
        encodedSecret: String,
        filePermissions: JvmSecretFilePermissions,
    ) {
        if (Files.readString(path) != encodedSecret) {
            throw SecretFileWriteException("Could not verify pending $providerLabel secret file")
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
        } catch (error: SecretFileWriteException) {
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
}

private fun throwWriteException(
    providerLabel: String,
    path: Path,
    pendingPath: java.nio.file.Path,
    error: Throwable,
): Nothing {
    deleteBestEffort(pendingPath)
    throw SecretFileWriteException("Could not securely write $providerLabel secret file: $path", error)
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

private fun validateSecretDestinations(
    providerLabel: String,
    configuredPath: Path,
    primaryPath: java.nio.file.Path,
    pendingPath: java.nio.file.Path,
    backupPath: java.nio.file.Path,
) {
    listOf(primaryPath, pendingPath, backupPath).forEach { candidate ->
        val attributes = readSecretAttributes(providerLabel, configuredPath, candidate) ?: return@forEach
        if (!attributes.isRegularFile) {
            throw SecretFileWriteException(
                "$providerLabel secret destination must be a regular file: $configuredPath",
            )
        }
        if (candidate == primaryPath && hasMultipleHardLinks(providerLabel, configuredPath, candidate)) {
            throw SecretFileWriteException(
                "$providerLabel secret destination must not have multiple hard links: $configuredPath",
            )
        }
    }
}

private fun hasMultipleHardLinks(
    providerLabel: String,
    configuredPath: Path,
    candidate: java.nio.file.Path,
): Boolean {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) return false
    return try {
        (Files.getAttribute(candidate, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() > 1L
    } catch (error: IOException) {
        throwSecretInspectionFailure(providerLabel, configuredPath, error)
    } catch (error: SecurityException) {
        throwSecretInspectionFailure(providerLabel, configuredPath, error)
    }
}

private fun readSecretAttributes(
    providerLabel: String,
    configuredPath: Path,
    candidate: java.nio.file.Path,
): BasicFileAttributes? = try {
    Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
} catch (error: IOException) {
    throwSecretInspectionFailure(providerLabel, configuredPath, error)
} catch (error: SecurityException) {
    throwSecretInspectionFailure(providerLabel, configuredPath, error)
}

private fun throwSecretInspectionFailure(
    providerLabel: String,
    configuredPath: Path,
    error: Throwable,
): Nothing {
    val message = "Could not inspect $providerLabel secret destination: $configuredPath"
    throw SecretFileWriteException(message, error)
}

internal fun atomicReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
}
