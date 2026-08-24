package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState

internal data class EngHubPaneAvailability(
    val isEnabled: Boolean,
    val disabledReason: String? = null,
)

internal fun engHubPaneAvailability(
    settings: EngHubSettingsUiState,
): Map<EngHubPane, EngHubPaneAvailability> = EngHubPane.entries.associateWith { pane ->
    when (pane) {
        EngHubPane.PullRequests -> when {
            !settings.gitHubAccessReady -> disabledPane("Enter a GitHub secret path and token in Settings")
            settings.committedConfig.gitHubAuthor.isBlank() -> disabledPane("Enter a GitHub author in Settings")
            else -> enabledPane()
        }

        EngHubPane.Notifications -> if (settings.gitHubAccessReady) {
            enabledPane()
        } else {
            disabledPane("Enter a GitHub secret path and token in Settings")
        }

        EngHubPane.Worktrees -> if (settings.hasValidCommittedLocalRepositoryPaths()) {
            enabledPane()
        } else {
            disabledPane("Fix local repository paths in Settings")
        }

        EngHubPane.Settings -> enabledPane()
    }
}

private fun EngHubSettingsUiState.hasValidCommittedLocalRepositoryPaths(): Boolean {
    val normalizedPaths = committedConfig.localRepositories.map { it.path.normalizedRepositoryPath() }
    return normalizedPaths.none { it.isBlank() } && normalizedPaths.distinct().size == normalizedPaths.size
}

private fun enabledPane() = EngHubPaneAvailability(isEnabled = true)

private fun disabledPane(reason: String) = EngHubPaneAvailability(isEnabled = false, disabledReason = reason)
