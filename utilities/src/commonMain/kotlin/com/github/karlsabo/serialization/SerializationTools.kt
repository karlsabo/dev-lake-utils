package com.github.karlsabo.serialization

import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.core.writeText
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.KSerializer

@PublishedApi
internal val logger = KotlinLogging.logger {}

fun <T> saveConfig(
    configPath: Path,
    config: T,
    serializer: KSerializer<T>,
) {
    SystemFileSystem.sink(configPath).buffered()
        .writeText(lenientJson.encodeToString(serializer, config))
}

inline fun <reified T> loadConfig(configPath: Path, serializer: KSerializer<T>): T? {
    val loadedConfig = if (SystemFileSystem.exists(configPath)) {
        runCatching {
            lenientJson.decodeFromString(serializer, SystemFileSystem.source(configPath).buffered().readText())
        }.onFailure { error ->
            logger.error(error) { "Failed to load config: $configPath" }
        }.getOrNull()
    } else {
        null
    }

    return loadedConfig
}
