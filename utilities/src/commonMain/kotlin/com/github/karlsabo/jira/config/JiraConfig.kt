package com.github.karlsabo.jira.config

import com.github.karlsabo.Credentials
import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

/**
 * Runtime configuration for Jira REST API.
 */
data class JiraApiRestConfig(
    val credentials: Credentials,
    val domain: String,
)

/**
 * Configuration for Jira API loaded from a file.
 */
@Serializable
data class JiraConfig(
    val domain: String,
    val username: String,
    val apiKeyPath: String,
)

/**
 * Secret configuration for Jira API.
 */
@Serializable
data class JiraSecret(val jiraApiKey: String)

/**
 * Loads Jira configuration from a file.
 */
fun loadJiraConfig(configFilePath: Path): JiraApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<JiraConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.apiKeyPath)).buffered().use { source ->
        lenientJson.decodeFromString<JiraSecret>(source.readText())
    }

    return JiraApiRestConfig(
        Credentials(
            config.username,
            secretConfig.jiraApiKey,
        ),
        config.domain,
    )
}

/**
 * Saves Jira configuration to a file.
 */
fun saveJiraConfig(configPath: Path, config: JiraConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(JiraConfig.serializer(), config))
    }
}
