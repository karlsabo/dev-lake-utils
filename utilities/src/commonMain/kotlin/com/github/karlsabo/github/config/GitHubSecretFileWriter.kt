package com.github.karlsabo.github.config

import com.github.karlsabo.secret.SecretFileWriteException
import com.github.karlsabo.secret.createSecretFileWriter
import kotlinx.io.files.Path

private const val GITHUB_PROVIDER_LABEL = "GitHub"

open class GitHubSettingsWriteException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class GitHubSecretWriteException(
    message: String,
    cause: Throwable? = null,
) : GitHubSettingsWriteException(message, cause)

class GitHubConfigWriteException(
    message: String,
    cause: Throwable? = null,
) : GitHubSettingsWriteException(message, cause)

fun interface GitHubSecretFileWriter {
    @Throws(GitHubSecretWriteException::class)
    fun write(path: Path, encodedSecret: String)
}

internal fun createGitHubSecretFileWriter(): GitHubSecretFileWriter = GitHubSecretFileWriter { path, encodedSecret ->
    try {
        createSecretFileWriter(GITHUB_PROVIDER_LABEL).write(path, encodedSecret)
    } catch (error: SecretFileWriteException) {
        throw GitHubSecretWriteException(error.message ?: "Could not securely write GitHub secret file: $path", error)
    }
}
