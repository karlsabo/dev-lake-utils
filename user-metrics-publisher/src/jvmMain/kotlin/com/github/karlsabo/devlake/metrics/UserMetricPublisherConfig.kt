package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.DEV_LAKE_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import io.ktor.utils.io.core.writeText
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserMetricPublisherConfig(
    val userIds: List<String> = emptyList<String>(),
    val zapierMetricUrl: String = "https://hooks.zapier.com",
    val metricInformationPostfix: String = "",
)

val userMetricPublisherConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "user-metric-publisher-config.json")

fun loadUserMetricPublisherConfig(): UserMetricPublisherConfig {
    SystemFileSystem.source(userMetricPublisherConfigPath).buffered().readText().let {
        return Json.decodeFromString(UserMetricPublisherConfig.serializer(), it)
    }
}

val jsonEncoder = Json { encodeDefaults = true }
fun saveUserMetricPublisherConfig(config: UserMetricPublisherConfig) {
    SystemFileSystem.sink(userMetricPublisherConfigPath).buffered()
        .writeText(jsonEncoder.encodeToString(UserMetricPublisherConfig.serializer(), config))
}

