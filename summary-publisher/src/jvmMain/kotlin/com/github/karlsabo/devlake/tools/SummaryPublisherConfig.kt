package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.DEV_LAKE_APP_NAME
import com.github.karlsabo.devlake.dto.Project
import io.ktor.utils.io.core.writeText
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SummaryPublisherConfig(
    val zapierSummaryUrl: String = "https://example.local",
    val summaryName: String = "Project",
    val projects: List<Project> = emptyList(),
    val isMiscellaneousProjectIncluded: Boolean = true,
    val isPagerDutyIncluded: Boolean = true,
)

val summaryPublisherConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "summary-publisher-config.json")

fun loadSummaryPublisherConfig(configPath: Path = summaryPublisherConfigPath): SummaryPublisherConfig {
    SystemFileSystem.source(configPath).buffered().readText().let {
        return Json.decodeFromString(SummaryPublisherConfig.serializer(), it)
    }
}

val jsonEncoder = Json { encodeDefaults = true }
fun saveSummaryPublisherConfig(config: SummaryPublisherConfig) {
    SystemFileSystem.sink(summaryPublisherConfigPath).buffered()
        .writeText(jsonEncoder.encodeToString(SummaryPublisherConfig.serializer(), config))
}

