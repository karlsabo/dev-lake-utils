package com.github.karlsabo.devlake.ghpanel

import com.github.karlsabo.tools.DEV_METRICS_APP_NAME
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

@Serializable
data class GitHubControlPanelConfig(
    val organizationIds: List<String> = emptyList(),
    val pollIntervalMs: Long = 60_000,
    val repositoriesBaseDir: String = "",
    val gitHubAuthor: String = "",
)

val gitHubControlPanelConfigPath: Path =
    Path(getApplicationDirectory(DEV_METRICS_APP_NAME), "github-control-panel-config.json")

fun loadGitHubControlPanelConfig(): GitHubControlPanelConfig {
    val text = SystemFileSystem.source(gitHubControlPanelConfigPath).buffered().use { it.readString() }
    return lenientJson.decodeFromString(GitHubControlPanelConfig.serializer(), text)
}

fun saveGitHubControlPanelConfig(config: GitHubControlPanelConfig) {
    SystemFileSystem.sink(gitHubControlPanelConfigPath).buffered().use {
        it.writeString(lenientJson.encodeToString(GitHubControlPanelConfig.serializer(), config))
    }
}
