package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.Project
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
data class SummaryPublisherConfig(
    val zapierSummaryUrl: String = "https://example.local",
    val summaryName: String = "Project",
    val isTerseSummaryUsed: Boolean = true,
    val projects: List<Project> = emptyList(),
    val isMiscellaneousProjectIncluded: Boolean = true,
    val gitHubOrganizationIds: List<String> = emptyList(),
    val pagerDutyServiceIds: List<String> = emptyList(),
    val miscUserIds: List<String> = emptyList(),
)

val summaryPublisherConfigPath = Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "summary-publisher-config.json")

fun loadSummaryPublisherConfig(configPath: Path = summaryPublisherConfigPath): SummaryPublisherConfig {
    SystemFileSystem.source(configPath).buffered().readText().let {
        return lenientJson.decodeFromString(SummaryPublisherConfig.serializer(), it)
    }
}

fun saveSummaryPublisherConfig(config: SummaryPublisherConfig) {
    SystemFileSystem.sink(summaryPublisherConfigPath).buffered()
        .writeText(lenientJson.encodeToString(SummaryPublisherConfig.serializer(), config))
}

