package com.github.karlsabo.github.config

import kotlinx.io.files.Path

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

internal expect fun createGitHubSecretFileWriter(): GitHubSecretFileWriter
