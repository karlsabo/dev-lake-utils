package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

@Serializable(with = EngHubConfigSerializer::class)
data class EngHubConfig(
    val organizationIds: List<String> = emptyList(),
    val pollIntervalMs: Long = 10.minutes.inWholeMilliseconds,
    val worktreePollIntervalMs: Long = 2.minutes.inWholeMilliseconds,
    val repositoriesBaseDir: String = "",
    val gitHubAuthor: String = "",
    val planningMarkdownDir: String = "",
    val localRepositories: List<LocalRepositoryConfig> = emptyList(),
    val worktreeSetupCommands: Map<String, List<String>> = emptyMap(),
    val setupShell: String = "/bin/zsh",
)

object EngHubConfigSerializer : KSerializer<EngHubConfig> {
    override val descriptor: SerialDescriptor = EngHubConfigSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): EngHubConfig {
        val value = decoder.decodeSerializableValue(EngHubConfigSurrogate.serializer())
        return EngHubConfig(
            organizationIds = value.organizationIds,
            pollIntervalMs = value.pollIntervalMs,
            worktreePollIntervalMs = value.worktreePollIntervalMs,
            repositoriesBaseDir = value.repositoriesBaseDir,
            gitHubAuthor = value.gitHubAuthor,
            planningMarkdownDir = value.planningMarkdownDir,
            localRepositories = normalizeLocalRepositories(
                localRepositories = value.localRepositories,
                worktreeSetupCommands = value.worktreeSetupCommands,
            ),
            worktreeSetupCommands = value.worktreeSetupCommands,
            setupShell = value.setupShell,
        )
    }

    override fun serialize(encoder: Encoder, value: EngHubConfig) {
        encoder.encodeSerializableValue(
            EngHubConfigSurrogate.serializer(),
            EngHubConfigSurrogate(
                organizationIds = value.organizationIds,
                pollIntervalMs = value.pollIntervalMs,
                worktreePollIntervalMs = value.worktreePollIntervalMs,
                repositoriesBaseDir = value.repositoriesBaseDir,
                gitHubAuthor = value.gitHubAuthor,
                planningMarkdownDir = value.planningMarkdownDir,
                localRepositories = value.localRepositories,
                worktreeSetupCommands = value.worktreeSetupCommands,
                setupShell = value.setupShell,
            ),
        )
    }
}

@Serializable
private data class EngHubConfigSurrogate(
    val organizationIds: List<String> = emptyList(),
    val pollIntervalMs: Long = 10.minutes.inWholeMilliseconds,
    val worktreePollIntervalMs: Long = 2.minutes.inWholeMilliseconds,
    val repositoriesBaseDir: String = "",
    val gitHubAuthor: String = "",
    val planningMarkdownDir: String = "",
    val localRepositories: List<LocalRepositoryConfig> = emptyList(),
    val worktreeSetupCommands: Map<String, List<String>> = emptyMap(),
    val setupShell: String = "/bin/zsh",
)

@Serializable(with = LocalRepositoryConfigSerializer::class)
data class LocalRepositoryConfig(
    val path: String,
    val setupCommands: List<String> = emptyList(),
)

object LocalRepositoryConfigSerializer : KSerializer<LocalRepositoryConfig> {
    override val descriptor: SerialDescriptor = LocalRepositoryConfigSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocalRepositoryConfig {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("LocalRepositoryConfig can only be decoded from JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> LocalRepositoryConfig(path = element.jsonPrimitive.content)
            is JsonObject -> {
                val value = jsonDecoder.json.decodeFromJsonElement(
                    LocalRepositoryConfigSurrogate.serializer(),
                    element,
                )
                LocalRepositoryConfig(path = value.path, setupCommands = value.setupCommands)
            }

            else -> throw SerializationException("localRepositories entries must be strings or objects")
        }
    }

    override fun serialize(encoder: Encoder, value: LocalRepositoryConfig) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("LocalRepositoryConfig can only be encoded to JSON")
        jsonEncoder.encodeJsonElement(
            jsonEncoder.json.encodeToJsonElement(
                LocalRepositoryConfigSurrogate.serializer(),
                LocalRepositoryConfigSurrogate(path = value.path, setupCommands = value.setupCommands),
            )
        )
    }
}

@Serializable
private data class LocalRepositoryConfigSurrogate(
    val path: String,
    val setupCommands: List<String> = emptyList(),
)

private fun normalizeLocalRepositories(
    localRepositories: List<LocalRepositoryConfig>,
    worktreeSetupCommands: Map<String, List<String>>,
): List<LocalRepositoryConfig> {
    val legacyCommandsByNormalizedPath = mutableMapOf<String, List<String>>()
    worktreeSetupCommands.forEach { (path, commands) ->
        val normalizedPath = path.normalizedRepositoryPath()
        if (normalizedPath !in legacyCommandsByNormalizedPath) {
            legacyCommandsByNormalizedPath[normalizedPath] = commands
        }
    }
    val normalizedUnifiedPaths = mutableSetOf<String>()
    val normalizedRepositories = localRepositories.mapNotNull { repository ->
        val normalizedPath = repository.path.normalizedRepositoryPath()
        if (!normalizedUnifiedPaths.add(normalizedPath)) return@mapNotNull null

        if (repository.setupCommands.isEmpty()) {
            repository.copy(setupCommands = legacyCommandsByNormalizedPath[normalizedPath].orEmpty())
        } else {
            repository
        }
    }

    val legacyRepositories = worktreeSetupCommands.entries
        .filter { it.key.normalizedRepositoryPath() !in normalizedUnifiedPaths }
        .distinctBy { it.key.normalizedRepositoryPath() }
        .map { LocalRepositoryConfig(path = it.key, setupCommands = it.value) }

    return normalizedRepositories + legacyRepositories
}

internal fun String.normalizedRepositoryPath(): String = trim().trimEnd('/', '\\')

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

fun interface EngHubConfigWriter {
    fun save(config: EngHubConfig)
}
