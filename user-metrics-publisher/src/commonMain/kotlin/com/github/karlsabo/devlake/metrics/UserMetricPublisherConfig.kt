package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.core.writeText
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

@Serializable
data class UserMetricPublisherConfig(
    val userIds: List<String> = emptyList(),
    val organizationIds: List<String> = emptyList(),
    val zapierMetricUrl: String = "https://hooks.zapier.com",
    val metricInformationPostfix: String = "",
)

val userMetricPublisherConfigPath =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "user-metric-publisher-config.json")

fun loadUserMetricPublisherConfig(): UserMetricPublisherConfig {
    SystemFileSystem.source(userMetricPublisherConfigPath).buffered().readText().let {
        return lenientJson.decodeFromString(UserMetricPublisherConfig.serializer(), it)
    }
}

fun saveUserMetricPublisherConfig(config: UserMetricPublisherConfig) {
    SystemFileSystem.sink(userMetricPublisherConfigPath).buffered()
        .writeText(lenientJson.encodeToString(UserMetricPublisherConfig.serializer(), config))
}

