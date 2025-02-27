package com.github.karlsabo.serialization

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }
fun <T> saveConfig(configPath: Path, config: T, serializer: KSerializer<T>) {
    SystemFileSystem.sink(configPath).buffered()
        .writeText(json.encodeToString(serializer, config))
}

inline fun <reified T> loadConfig(configPath: Path, serializer: KSerializer<T>): T? {
    if (!SystemFileSystem.exists(configPath)) {
        return null
    }
    return try {
        Json.decodeFromString(serializer, SystemFileSystem.source(configPath).buffered().readText())
    } catch (error: Exception) {
        println("Failed to load config: $error, $configPath")
        return null
    }
}
