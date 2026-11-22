package com.github.karlsabo.secret

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.fchmod
import platform.posix.fstat
import platform.posix.open
import platform.posix.stat
import platform.posix.unlink

internal fun createOwnerOnlySecretFile(path: Path, providerLabel: String): Int {
    val mode = ownerReadWriteMode()
    val descriptor = open(path.toString(), O_WRONLY or O_CREAT or O_EXCL, mode)
    requireOpenDescriptor(descriptor, path, providerLabel)
    try {
        configureOwnerOnlyDescriptor(descriptor, mode, path, providerLabel)
        return descriptor
    } catch (error: SecretFileWriteException) {
        close(descriptor)
        unlink(path.toString())
        throw error
    }
}

internal fun ownerReadWriteMode(): platform.posix.mode_t = OWNER_MODE.convert()

internal fun secureWriteFailure(path: Path, providerLabel: String): SecretFileWriteException {
    val message = "Could not securely write $providerLabel secret file: $path"
    return SecretFileWriteException(message)
}

private fun requireOpenDescriptor(
    descriptor: Int,
    path: Path,
    providerLabel: String,
) {
    if (descriptor == -1) throw secureWriteFailure(path, providerLabel)
}

private fun configureOwnerOnlyDescriptor(
    descriptor: Int,
    mode: platform.posix.mode_t,
    path: Path,
    providerLabel: String,
) {
    if (fchmod(descriptor, mode) != 0) throw secureWriteFailure(path, providerLabel)
    verifyOwnerOnlyDescriptor(descriptor, path, providerLabel)
}

private fun verifyOwnerOnlyDescriptor(
    descriptor: Int,
    path: Path,
    providerLabel: String,
) = memScoped {
    val fileStatus = alloc<stat>()
    if (fstat(descriptor, fileStatus.ptr) != 0 || fileStatus.st_mode.toInt() and PERMISSION_MASK != OWNER_MODE) {
        throw SecretFileWriteException(
            "$providerLabel secret file permissions could not be restricted to its owner: $path",
        )
    }
}

private const val PERMISSION_MASK = 0b1_1111_1111
private val OWNER_MODE = S_IRUSR or S_IWUSR
