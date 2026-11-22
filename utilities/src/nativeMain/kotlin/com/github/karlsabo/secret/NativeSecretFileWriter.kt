package com.github.karlsabo.secret

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.ENOENT
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.lstat
import platform.posix.stat
import platform.posix.write

internal actual fun createSecretFileWriter(providerLabel: String): SecretFileWriter {
    val writer = NativeSecretFileWriter(providerLabel)
    return writer
}

private class NativeSecretFileWriter(
    private val providerLabel: String,
) : SecretFileWriter {
    override fun write(path: Path, encodedSecret: String) {
        val pendingPath = Path("$path.new")
        val backupPath = Path("$path.bak")
        validateDestinations(providerLabel, path, pendingPath, backupPath)
        try {
            deleteBestEffort(pendingPath)
            writePendingSecret(pendingPath, encodedSecret)
            verifyPendingSecret(pendingPath, encodedSecret)
            promoteSecret(pendingPath, path, backupPath)
        } catch (error: SecretFileWriteException) {
            deleteBestEffort(pendingPath)
            throw error
        } catch (error: IOException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        } catch (error: UnsupportedOperationException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        } catch (error: IllegalArgumentException) {
            throwWriteException(providerLabel, path, pendingPath, error)
        }
    }

    private fun writePendingSecret(path: Path, encodedSecret: String) {
        val descriptor = createOwnerOnlySecretFile(path, providerLabel)
        try {
            writeAll(providerLabel, descriptor, encodedSecret.encodeToByteArray(), path)
            synchronize(providerLabel, descriptor, path)
            closeSecureFile(providerLabel, descriptor, path)
        } catch (error: SecretFileWriteException) {
            close(descriptor)
            deleteBestEffort(path)
            throw error
        }
    }

    private fun verifyPendingSecret(path: Path, encodedSecret: String) {
        val actual = SystemFileSystem.source(path).buffered().use { source -> source.readString() }
        if (actual != encodedSecret) {
            throw SecretFileWriteException("Could not verify pending $providerLabel secret file")
        }
    }

    private fun promoteSecret(
        pendingPath: Path,
        primaryPath: Path,
        backupPath: Path,
    ) {
        val rotatedPrimary = SystemFileSystem.exists(primaryPath)
        if (rotatedPrimary) {
            restrictToOwner(primaryPath)
            SystemFileSystem.atomicMove(primaryPath, backupPath)
        }
        try {
            SystemFileSystem.atomicMove(pendingPath, primaryPath)
            restrictToOwner(primaryPath)
        } catch (error: IOException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        } catch (error: UnsupportedOperationException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        } catch (error: SecretFileWriteException) {
            restoreAfterPromotionFailure(rotatedPrimary, backupPath, primaryPath, error)
        }
    }

    private fun restoreAfterPromotionFailure(
        rotatedPrimary: Boolean,
        backupPath: Path,
        primaryPath: Path,
        error: Throwable,
    ): Nothing {
        if (rotatedPrimary) {
            restoreBackupBestEffort(backupPath, primaryPath)
        } else {
            deleteBestEffort(primaryPath)
        }
        throw error
    }

    private fun restoreBackupBestEffort(backupPath: Path, primaryPath: Path) {
        try {
            SystemFileSystem.atomicMove(backupPath, primaryPath)
        } catch (_: IOException) {
            // The promotion failure is the useful error to report.
        } catch (_: UnsupportedOperationException) {
            // The promotion failure is the useful error to report.
        }
    }

    private fun restrictToOwner(path: Path) {
        if (chmod(path.toString(), ownerReadWriteMode()) != 0) {
            throw SecretFileWriteException(
                "$providerLabel secret file permissions could not be restricted to its owner: $path",
            )
        }
    }
}

private fun validateDestinations(
    providerLabel: String,
    primaryPath: Path,
    pendingPath: Path,
    backupPath: Path,
) {
    listOf(primaryPath, pendingPath, backupPath).forEach { candidate ->
        validateRegularFileIfPresent(providerLabel, primaryPath, candidate)
    }
}

private fun validateRegularFileIfPresent(
    providerLabel: String,
    configuredPath: Path,
    candidate: Path,
) = memScoped {
    val fileStatus = alloc<stat>()
    when {
        lstat(candidate.toString(), fileStatus.ptr) == 0 -> {
            val fileType = fileStatus.st_mode.toInt() and S_IFMT
            if (fileType != S_IFREG) {
                throw SecretFileWriteException(
                    "$providerLabel secret destination must be a regular file: $configuredPath",
                )
            }
            if (candidate == configuredPath && fileStatus.st_nlink.toLong() > 1L) {
                throw SecretFileWriteException(
                    "$providerLabel secret destination must not have multiple hard links: $configuredPath",
                )
            }
        }

        errno != ENOENT -> throw SecretFileWriteException(
            "Could not inspect $providerLabel secret destination: $configuredPath",
        )
    }
}

private fun throwWriteException(
    providerLabel: String,
    path: Path,
    pendingPath: Path,
    error: Throwable,
): Nothing {
    deleteBestEffort(pendingPath)
    throw SecretFileWriteException("Could not securely write $providerLabel secret file: $path", error)
}

private fun deleteBestEffort(path: Path) {
    try {
        SystemFileSystem.delete(path, mustExist = false)
    } catch (_: IOException) {
        // Cleanup must not hide the storage failure.
    }
}

private fun writeAll(
    providerLabel: String,
    descriptor: Int,
    content: ByteArray,
    path: Path,
) {
    content.usePinned { pinnedContent ->
        var offset = 0
        while (offset < content.size) {
            val written = write(
                descriptor,
                pinnedContent.addressOf(offset),
                (content.size - offset).convert(),
            )
            if (written <= 0) throw secureWriteFailure(path, providerLabel)
            offset += written.toInt()
        }
    }
}

private fun synchronize(
    providerLabel: String,
    descriptor: Int,
    path: Path,
) {
    if (fsync(descriptor) != 0) throw secureWriteFailure(path, providerLabel)
}

private fun closeSecureFile(
    providerLabel: String,
    descriptor: Int,
    path: Path,
) {
    if (close(descriptor) != 0) throw secureWriteFailure(path, providerLabel)
}
