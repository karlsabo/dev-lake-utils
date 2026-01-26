package com.github.karlsabo.linear.config

import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

private const val DEFAULT_LINEAR_ENDPOINT = "https://api.linear.app/graphql"

/**
 * Configuration for Linear GraphQL API.
 */
data class LinearApiRestConfig(
    val token: String,
    val endpoint: String = DEFAULT_LINEAR_ENDPOINT,
    val useBearerAuth: Boolean = false,
) {
    override fun toString(): String {
        return "LinearApiRestConfig(endpoint=$endpoint, useBearerAuth=$useBearerAuth)"
    }
}

/**
 * Configuration for Linear API loaded from a file.
 */
@Serializable
data class LinearConfig(
    val tokenPath: String,
    val endpoint: String? = null,
    val useBearerAuth: Boolean? = null,
)

/**
 * Secret configuration for Linear API.
 */
@Serializable
data class LinearSecret(
    val linearApiKey: String,
)

/**
 * Loads Linear configuration from a file.
 */
fun loadLinearConfig(configFilePath: Path): LinearApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<LinearConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<LinearSecret>(source.readText())
    }

    return LinearApiRestConfig(
        token = secretConfig.linearApiKey,
        endpoint = config.endpoint ?: DEFAULT_LINEAR_ENDPOINT,
        useBearerAuth = config.useBearerAuth ?: false,
    )
}

/**
 * Saves Linear configuration to a file.
 */
@Suppress("unused")
fun saveLinearConfig(configPath: Path, config: LinearConfig) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(LinearConfig.serializer(), config))
    }
}
