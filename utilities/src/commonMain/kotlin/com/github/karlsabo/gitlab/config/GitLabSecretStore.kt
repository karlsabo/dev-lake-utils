package com.github.karlsabo.gitlab.config

import com.github.karlsabo.secret.SecretFileWriteException
import com.github.karlsabo.secret.createSecretFileWriter
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.files.Path

private const val GITLAB_PROVIDER_LABEL = "GitLab"

open class GitLabSettingsWriteException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class GitLabSecretWriteException(
    message: String,
    cause: Throwable? = null,
) : GitLabSettingsWriteException(message, cause)

fun interface GitLabSecretFileWriter {
    @Throws(GitLabSecretWriteException::class)
    fun write(path: Path, encodedSecret: String)
}

internal fun createGitLabSecretFileWriter(): GitLabSecretFileWriter = GitLabSecretFileWriter { path, encodedSecret ->
    try {
        createSecretFileWriter(GITLAB_PROVIDER_LABEL).write(path, encodedSecret)
    } catch (error: SecretFileWriteException) {
        throw GitLabSecretWriteException(error.message ?: "Could not securely write GitLab secret file: $path", error)
    }
}

/**
 * Persists the GitLab personal access token in its own protected file, separate from GitHub's secret.
 */
class GitLabSecretStore(
    private val secretFileWriter: GitLabSecretFileWriter = createGitLabSecretFileWriter(),
) {
    fun save(secretPath: Path, secret: GitLabSecret) {
        secretFileWriter.write(secretPath, lenientJson.encodeToString(GitLabSecret.serializer(), secret))
    }
}
