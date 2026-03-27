package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

@Serializable
data class EngHubConfig(
    val organizationIds: List<String> = emptyList(),
    val pollIntervalMs: Long = 10.minutes.inWholeMilliseconds,
    val repositoriesBaseDir: String = "",
    val gitHubAuthor: String = "",
    val worktreeSetupCommands: Map<String, List<String>> = emptyMap(),
    val setupShell: String = "/bin/zsh",
)

val engHubConfigPath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "eng-hub-config.json")

fun loadEngHubConfig(): EngHubConfig {
    val text = SystemFileSystem.source(engHubConfigPath).buffered().use { it.readString() }
    return lenientJson.decodeFromString(EngHubConfig.serializer(), text)
}

fun saveEngHubConfig(config: EngHubConfig) {
    SystemFileSystem.sink(engHubConfigPath).buffered().use {
        it.writeString(lenientJson.encodeToString(EngHubConfig.serializer(), config))
    }
}
