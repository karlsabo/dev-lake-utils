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

@Serializable
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

@Serializable(with = LocalRepositoryConfigSerializer::class)
data class LocalRepositoryConfig(
    val path: String,
    val setupCommands: List<String> = emptyList(),
)

private object LocalRepositoryConfigSerializer : KSerializer<LocalRepositoryConfig> {
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
