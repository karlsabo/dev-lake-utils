package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.readText
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Configuration for GitHub REST API.
 */
data class GitHubApiRestConfig(
    val token: String,
) {
    override fun toString(): String = "GitHubApiRestConfig()"
}

/**
 * Configuration for GitHub API loaded from a file.
 */
@Serializable
data class GitHubConfig(
    val tokenPath: String,
)

/**
 * Secret configuration for GitHub API.
 */
@Serializable
data class GitHubSecret(
    val githubToken: String,
) {
    override fun toString(): String = "GitHubSecret()"
}

data class LoadedGitHubConfig(
    val config: GitHubConfig,
    val secret: GitHubSecret,
) {
    fun toApiRestConfig(): GitHubApiRestConfig = GitHubApiRestConfig(secret.githubToken)
}

/**
 * Loads recoverable GitHub settings. Configuration may fall back to its backup; secrets never do.
 */
fun loadGitHubSettings(configFilePath: Path): LoadedGitHubConfig {
    val loadedSettings = loadGitHubSettingsIfPresent(configFilePath)
    return requireNotNull(loadedSettings) { "No valid GitHub configuration found at $configFilePath" }
}

fun loadGitHubSettingsIfPresent(
    configFilePath: Path,
    fileSystem: FileSystem = SystemFileSystem,
): LoadedGitHubConfig? {
    val config = decodeGitHubConfigIfValid(configFilePath, fileSystem)
        ?: decodeGitHubConfigIfValid(Path("$configFilePath.bak"), fileSystem)
        ?: return null
    val secret = if (config.tokenPath.isBlank()) {
        GitHubSecret("")
    } else {
        decodeGitHubSecretIfValid(Path(config.tokenPath), fileSystem) ?: GitHubSecret("")
    }
    return LoadedGitHubConfig(config = config, secret = secret)
}

internal fun decodeGitHubConfigIfValid(
    path: Path,
    fileSystem: FileSystem = SystemFileSystem,
): GitHubConfig? = decodeIfValid(path, fileSystem)

private fun decodeGitHubSecretIfValid(path: Path, fileSystem: FileSystem): GitHubSecret? {
    val decodedSecret = decodeIfValid<GitHubSecret>(path, fileSystem)
    return decodedSecret
}

private inline fun <reified T> decodeIfValid(path: Path, fileSystem: FileSystem): T? {
    return try {
        if (!fileSystem.exists(path)) return null
        fileSystem.source(path).buffered().use { source ->
            lenientJson.decodeFromString<T>(source.readText())
        }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Loads GitHub configuration for an API client.
 */
fun loadGitHubConfig(configFilePath: Path): GitHubApiRestConfig = loadGitHubSettings(configFilePath).toApiRestConfig()

/**
 * Saves GitHub configuration to a file.
 */
fun saveGitHubConfig(configPath: Path, config: GitHubConfig) {
    GitHubConfigStore().saveConfig(configPath, config)
}
