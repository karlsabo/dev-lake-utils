package com.github.karlsabo.secret

import kotlinx.io.files.Path

open class SecretFileWriteException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Writes an encoded secret to a file only its owner can read, replacing atomically and keeping the previous
 * contents as a backup. [providerLabel] only appears in diagnostics.
 */
fun interface SecretFileWriter {
    @Throws(SecretFileWriteException::class)
    fun write(path: Path, encodedSecret: String)
}

internal expect fun createSecretFileWriter(providerLabel: String): SecretFileWriter
