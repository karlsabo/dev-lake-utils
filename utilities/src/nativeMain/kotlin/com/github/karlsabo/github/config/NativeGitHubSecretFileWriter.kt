package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
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
import kotlinx.serialization.SerializationException
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

internal actual fun createGitHubSecretFileWriter(): GitHubSecretFileWriter = NativeGitHubSecretFileWriter()

private class NativeGitHubSecretFileWriter : GitHubSecretFileWriter {
    override fun write(path: Path, encodedSecret: String) {
        val pendingPath = Path("$path.new")
        val backupPath = Path("$path.bak")
        validateDestinations(path, pendingPath, backupPath)
        try {
            deleteBestEffort(pendingPath)
            writePendingSecret(pendingPath, encodedSecret)
            verifyPendingSecret(pendingPath, encodedSecret)
            promoteSecret(pendingPath, path, backupPath)
        } catch (error: GitHubSecretWriteException) {
            deleteBestEffort(pendingPath)
            throw error
        } catch (error: IOException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: UnsupportedOperationException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: SerializationException) {
            throwWriteException(path, pendingPath, error)
        } catch (error: IllegalArgumentException) {
            throwWriteException(path, pendingPath, error)
        }
    }

    private fun validateDestinations(
        primaryPath: Path,
        pendingPath: Path,
        backupPath: Path,
    ) {
        listOf(primaryPath, pendingPath, backupPath).forEach { candidate ->
            validateRegularFileIfPresent(primaryPath, candidate)
        }
    }

    private fun writePendingSecret(path: Path, encodedSecret: String) {
        val descriptor = createOwnerOnlySecretFile(path)
        try {
            writeAll(descriptor, encodedSecret.encodeToByteArray(), path)
            synchronize(descriptor, path)
            closeSecureFile(descriptor, path)
        } catch (error: GitHubSecretWriteException) {
            close(descriptor)
            deleteBestEffort(path)
            throw error
        }
    }

    private fun verifyPendingSecret(path: Path, encodedSecret: String) {
        val expected = lenientJson.decodeFromString<GitHubSecret>(encodedSecret)
        val actual = SystemFileSystem.source(path).buffered().use { source ->
            lenientJson.decodeFromString<GitHubSecret>(source.readString())
        }
        if (actual != expected) {
            throw GitHubSecretWriteException("Could not verify pending GitHub secret file")
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
        } catch (error: GitHubSecretWriteException) {
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
            throw GitHubSecretWriteException(
                "GitHub secret file permissions could not be restricted to its owner: $path",
            )
        }
    }

    private fun throwWriteException(
        path: Path,
        pendingPath: Path,
        error: Throwable,
    ): Nothing {
        deleteBestEffort(pendingPath)
        throw GitHubSecretWriteException("Could not securely write GitHub secret file: $path", error)
    }

    private fun deleteBestEffort(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (_: IOException) {
            // Cleanup must not hide the storage failure.
        }
    }
}

private fun validateRegularFileIfPresent(configuredPath: Path, candidate: Path) = memScoped {
    val fileStatus = alloc<stat>()
    when {
        lstat(candidate.toString(), fileStatus.ptr) == 0 -> {
            val fileType = fileStatus.st_mode.toInt() and S_IFMT
            if (fileType != S_IFREG) {
                throw GitHubSecretWriteException(
                    "GitHub secret destination must be a regular file: $configuredPath",
                )
            }
            if (candidate == configuredPath && fileStatus.st_nlink.toLong() > 1L) {
                throw GitHubSecretWriteException(
                    "GitHub secret destination must not have multiple hard links: $configuredPath",
                )
            }
        }

        errno != ENOENT -> throw GitHubSecretWriteException(
            "Could not inspect GitHub secret destination: $configuredPath",
        )
    }
}

private fun writeAll(
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
            if (written <= 0) throw secureWriteFailure(path)
            offset += written.toInt()
        }
    }
}

private fun synchronize(descriptor: Int, path: Path) {
    if (fsync(descriptor) != 0) throw secureWriteFailure(path)
}

private fun closeSecureFile(descriptor: Int, path: Path) {
    if (close(descriptor) != 0) throw secureWriteFailure(path)
}
