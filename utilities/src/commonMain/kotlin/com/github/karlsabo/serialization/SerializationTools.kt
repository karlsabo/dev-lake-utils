package com.github.karlsabo.serialization

import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.core.writeText
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.KSerializer

fun <T> saveConfig(configPath: Path, config: T, serializer: KSerializer<T>) {
    SystemFileSystem.sink(configPath).buffered()
        .writeText(lenientJson.encodeToString(serializer, config))
}

inline fun <reified T> loadConfig(configPath: Path, serializer: KSerializer<T>): T? {
    if (!SystemFileSystem.exists(configPath)) {
        return null
    }
    return try {
        lenientJson.decodeFromString(serializer, SystemFileSystem.source(configPath).buffered().readText())
    } catch (error: Exception) {
        println("Failed to load config: $error, $configPath")
        return null
    }
}
