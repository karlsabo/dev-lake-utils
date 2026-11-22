package com.github.karlsabo.gitlab.config

import com.github.karlsabo.gitlab.normalizeGitLabInstanceUrl
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Configuration for the GitLab REST API.
 */
data class GitLabApiRestConfig(
    val instanceUrl: String,
    val token: String,
) {
    val apiBaseUrl: String
        get() = "${instanceUrl.trimEnd('/')}/api/v4"

    override fun toString(): String = "GitLabApiRestConfig(instanceUrl=$instanceUrl)"
}

/**
 * Secret configuration for the GitLab REST API.
 */
@Serializable
data class GitLabSecret(
    val gitLabToken: String,
) {
    override fun toString(): String = "GitLabSecret()"
}

data class LoadedGitLabConfig(
    val instanceUrl: String,
    val tokenPath: String,
    val secret: GitLabSecret,
) {
    fun toApiRestConfig(): GitLabApiRestConfig? = normalizeGitLabInstanceUrl(instanceUrl)?.let { normalizedUrl ->
        GitLabApiRestConfig(normalizedUrl, secret.gitLabToken)
    }

    fun isAccessReady(): Boolean = tokenPath.isNotBlank() &&
        secret.gitLabToken.isNotBlank() &&
        toApiRestConfig() != null

    override fun toString(): String = "LoadedGitLabConfig(instanceUrl=$instanceUrl, tokenPath=$tokenPath)"
}

/**
 * Loads the secret referenced by the configured GitLab token path. Neither configuration nor secrets fall back to a
 * backup; a GitLab secret is only trusted from its primary file.
 */
fun loadGitLabSecretIfPresent(
    secretPath: Path,
    fileSystem: FileSystem = SystemFileSystem,
): GitLabSecret? {
    if (secretPath.toString().isBlank()) return null
    return try {
        if (!fileSystem.exists(secretPath)) {
            null
        } else {
            fileSystem.source(secretPath).buffered().use { source ->
                lenientJson.decodeFromString<GitLabSecret>(source.readString())
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
