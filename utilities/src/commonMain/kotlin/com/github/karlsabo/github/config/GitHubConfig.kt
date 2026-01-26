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
    override fun toString(): String {
        return "GitHubApiRestConfig()"
    }
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
)

/**
 * Loads GitHub configuration from a file.
 */
fun loadGitHubConfig(configFilePath: Path): GitHubApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<GitHubSecret>(source.readText())
    }

    return GitHubApiRestConfig(
        secretConfig.githubToken,
    )
}

/**
 * Saves GitHub configuration to a file.
 */
@Suppress("unused")
fun saveGitHubConfig(configPath: Path, config: GitHubConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(GitHubConfig.serializer(), config))
    }
}
