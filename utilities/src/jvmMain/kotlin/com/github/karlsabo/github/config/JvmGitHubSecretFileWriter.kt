package com.github.karlsabo.github.config

import com.github.karlsabo.secret.JvmSecretFileWriter
import com.github.karlsabo.secret.SecretFileWriteException
import com.github.karlsabo.secret.atomicReplace
import kotlinx.io.files.Path

internal class JvmGitHubSecretFileWriter(
    moveFile: (java.nio.file.Path, java.nio.file.Path) -> Unit = ::atomicReplace,
) : GitHubSecretFileWriter {
    private val delegate = JvmSecretFileWriter(PROVIDER_LABEL, moveFile)

    override fun write(path: Path, encodedSecret: String) {
        try {
            delegate.write(path, encodedSecret)
        } catch (error: SecretFileWriteException) {
            throw GitHubSecretWriteException(
                error.message ?: "Could not securely write GitHub secret file: $path",
                error,
            )
        }
    }

    private companion object {
        const val PROVIDER_LABEL = "GitHub"
    }
}
