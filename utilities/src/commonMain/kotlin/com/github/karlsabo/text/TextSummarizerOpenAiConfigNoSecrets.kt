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
    val apiKey = readOpenAiApiKey(apiKeyFilePath)
    return TextSummarizerOpenAiConfig(apiKey = apiKey)
}

private fun readOpenAiApiKey(apiKeyFilePath: String): String {
    val apiKeyPath = Path(apiKeyFilePath)
    return SystemFileSystem.source(apiKeyPath).buffered().use { it.readText() }
}

fun loadTextSummarizerOpenAiNoSecrets(
    configPath: Path,
): TextSummarizerOpenAiConfigNoSecrets? = loadConfig(
    configPath,
    TextSummarizerOpenAiConfigNoSecrets.serializer(),
)

fun saveTextSummarizerOpenAiNoSecrets(configPath: Path, config: TextSummarizerOpenAiConfigNoSecrets) {
    saveConfig(configPath, config, TextSummarizerOpenAiConfigNoSecrets.serializer())
}
