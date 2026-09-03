@file:OptIn(ExperimentalSerializationApi::class)

package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.minutes

internal const val ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY = "ALERT_TRIAGE_WHERE_TO_LOOK"
internal val LLM_TEMPLATE_KEYS = setOf(ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY)

@Serializable
data class EngHubConfig(
    val organizationIds: List<String> = emptyList(),
    val pollIntervalMs: Long = 10.minutes.inWholeMilliseconds,
    val worktreePollIntervalMs: Long = 2.minutes.inWholeMilliseconds,
    val repositoriesBaseDir: String = "",
    val gitHubAuthor: String = "",
    val planningMarkdownDir: String = "",
    val llmTemplateValues: Map<String, String> = emptyMap(),
    val localRepositories: List<LocalRepositoryConfig> = emptyList(),
    val setupShell: String = defaultSetupShell(),
)

internal fun defaultSetupShell(family: OsFamily = osFamily()): String = when (family) {
    OsFamily.WINDOWS -> "powershell.exe"
    else -> "/bin/zsh"
}

@Serializable
data class LocalRepositoryConfig(
    val path: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val setupCommands: List<String> = emptyList(),
)

private const val MILLISECONDS_PER_WHOLE_SECOND = 1_000L

internal fun EngHubConfig.isPersistenceValid(family: OsFamily = osFamily()): Boolean {
    val intervalsAreValid = pollIntervalMs > 0 &&
        pollIntervalMs % MILLISECONDS_PER_WHOLE_SECOND == 0L &&
        worktreePollIntervalMs > 0 &&
        worktreePollIntervalMs % MILLISECONDS_PER_WHOLE_SECOND == 0L
    val normalizedOrganizations = organizationIds.map { it.trim().lowercase() }
    val organizationsAreValid = organizationIds.none(String::isBlank) &&
        normalizedOrganizations.distinct().size == normalizedOrganizations.size
    val repositoriesHaveValues = localRepositories.none { repository ->
        repository.path.isBlank() || repository.setupCommands.any(String::isBlank)
    }
    val repositoryPaths = localRepositories.map { it.path.normalizedRepositoryPath(family) }
    val repositoryPathsAreUnique = repositoryPaths.distinct().size == repositoryPaths.size
    return intervalsAreValid && organizationsAreValid && repositoriesHaveValues && repositoryPathsAreUnique
}

val engHubConfigPath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "eng-hub-config.json")

fun loadEngHubConfig(): EngHubConfig = requireNotNull(loadEngHubConfigIfPresent()) {
    "No valid Eng Hub configuration found at $engHubConfigPath"
}

internal fun loadEngHubConfigIfPresent(
    configPath: Path = engHubConfigPath,
    family: OsFamily = osFamily(),
): EngHubConfig? {
    val primaryConfig = decodeEngHubConfigIfValid(configPath, family)
    return primaryConfig ?: decodeEngHubConfigIfValid(Path("$configPath.bak"), family)
}

internal fun decodeEngHubConfigIfValid(
    configPath: Path,
    family: OsFamily = osFamily(),
): EngHubConfig? = try {
    if (!SystemFileSystem.exists(configPath)) {
        null
    } else {
        SystemFileSystem.source(configPath).buffered().use { source ->
            lenientJson.decodeFromString(EngHubConfig.serializer(), source.readString())
                .takeIf { config -> config.isPersistenceValid(family) }
        }
    }
} catch (_: IOException) {
    null
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

fun saveEngHubConfig(config: EngHubConfig) {
    saveEngHubConfig(config, engHubConfigPath)
}

fun interface EngHubConfigWriter {
    fun save(config: EngHubConfig)
}
