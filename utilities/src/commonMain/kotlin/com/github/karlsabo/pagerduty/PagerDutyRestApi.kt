package com.github.karlsabo.pagerduty

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readText
import kotlinx.datetime.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Configuration for PagerDuty REST API.
 */
data class PagerDutyApiRestConfig(
    val apiKey: String,
) {
    override fun toString(): String {
        return "PagerDutyApiRestConfig()"
    }
}

/**
 * Configuration for PagerDuty API.
 */
@Serializable
data class PagerDutyConfig(
    val tokenPath: String,
)

/**
 * Secret configuration for PagerDuty API.
 */
@Serializable
data class PagerDutySecret(
    val pagerDutyApiKey: String,
)

/**
 * Loads PagerDuty configuration from a file.
 */
fun loadPagerDutyConfig(configFilePath: Path): PagerDutyApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        lenientJson.decodeFromString<PagerDutyConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.tokenPath)).buffered().use { source ->
        lenientJson.decodeFromString<PagerDutySecret>(source.readText())
    }

    return PagerDutyApiRestConfig(
        secretConfig.pagerDutyApiKey,
    )
}

/**
 * Saves PagerDuty configuration to a file.
 */
@Suppress("unused")
fun savePagerDutyConfig(config: PagerDutyConfig, configPath: Path) {
    SystemFileSystem.sink(configPath, false).buffered().use { sink ->
        sink.writeString(lenientJson.encodeToString(PagerDutyConfig.serializer(), config))
    }
}

/**
 * Implementation of the PagerDutyApi interface using REST.
 */
class PagerDutyRestApi(private val config: PagerDutyApiRestConfig) : PagerDutyApi {
    private val client: HttpClient = HttpClient(CIO) {
        install(Auth) {
        }
        install(ContentNegotiation) {
            json(lenientJson)
        }
        installHttpRetry()
        install(HttpCache)
        defaultRequest {
            header("Authorization", "Token token=${config.apiKey}")
            header("Accept", "application/vnd.pagerduty+json;version=2")
        }
        expectSuccess = false
    }

    override suspend fun getServicePages(
        serviceId: String,
        startTimeInclusive: Instant,
        endTimeExclusive: Instant,
    ): List<PagerDutyIncident> {
        val formattedStartTime = startTimeInclusive.toString()
        val formattedEndTime = endTimeExclusive.toString()

        val incidents = mutableListOf<PagerDutyIncident>()
        var offset = 0
        val limit = 25
        var moreItems = true

        while (moreItems) {
            val url = "https://api.pagerduty.com/incidents?service_ids[]=${serviceId.encodeURLParameter()}" +
                    "&since=${formattedStartTime.encodeURLParameter()}" +
                    "&until=${formattedEndTime.encodeURLParameter()}" +
                    "&limit=$limit&offset=$offset"

            val response = client.get(url)

            val status = response.status.value
            val responseText = response.bodyAsText()
            println("PagerDuty response: ```$responseText```")
            if (status !in 200..299) {
                println("Failed to get PagerDuty incidents: $status")
                throw Exception("Failed to get PagerDuty incidents: $status")
            }

            val root = lenientJson.parseToJsonElement(responseText).jsonObject
            val items = root["incidents"]?.jsonArray ?: break

            if (items.isEmpty()) {
                moreItems = false
            } else {
                for (item in items) {
                    incidents.add(lenientJson.decodeFromString(PagerDutyIncident.serializer(), item.toString()))
                }
                offset += limit

                val more = root["more"]?.jsonPrimitive?.booleanOrNull ?: false
                moreItems = more
            }
        }

        return incidents
    }
}
