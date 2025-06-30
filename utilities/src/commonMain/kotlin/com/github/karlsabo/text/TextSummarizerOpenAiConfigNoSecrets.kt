package com.github.karlsabo.text

import com.github.karlsabo.serialization.loadConfig
import com.github.karlsabo.serialization.saveConfig
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

@Serializable
data class TextSummarizerOpenAiConfigNoSecrets(
    val apiKeyFilePath: String,
)

fun TextSummarizerOpenAiConfigNoSecrets.toTextSummarizerOpenAiConfig(): TextSummarizerOpenAiConfig {
    return TextSummarizerOpenAiConfig(
        apiKey = SystemFileSystem.source(Path(this.apiKeyFilePath)).buffered().use { it.readText() },
    )
}

fun loadTextSummarizerOpenAiNoSecrets(configPath: Path): TextSummarizerOpenAiConfigNoSecrets? {
    return loadConfig(configPath, TextSummarizerOpenAiConfigNoSecrets.serializer())
}

fun saveTextSummarizerOpenAiNoSecrets(configPath: Path, config: TextSummarizerOpenAiConfigNoSecrets) {
    saveConfig(configPath, config, TextSummarizerOpenAiConfigNoSecrets.serializer())
}
