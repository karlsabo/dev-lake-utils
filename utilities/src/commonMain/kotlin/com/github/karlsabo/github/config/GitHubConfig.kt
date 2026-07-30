package com.github.karlsabo.github.config

import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

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
 * Loads the serializable GitHub configuration and its referenced secret.
 */
fun loadGitHubSettings(configFilePath: Path): LoadedGitHubConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubConfig>(source.readText())
    }
    val secret = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubSecret>(source.readText())
    }
    return LoadedGitHubConfig(config = config, secret = secret)
}

/**
 * Loads GitHub configuration for an API client.
 */
fun loadGitHubConfig(configFilePath: Path): GitHubApiRestConfig = loadGitHubSettings(configFilePath).toApiRestConfig()

/**
 * Saves GitHub configuration to a file.
 */
fun saveGitHubConfig(configPath: Path, config: GitHubConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(GitHubConfig.serializer(), config))
    }
}
