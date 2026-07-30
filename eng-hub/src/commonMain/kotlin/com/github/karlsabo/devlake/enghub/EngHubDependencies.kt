package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.github.config.loadGitHubSettings
import com.github.karlsabo.tools.gitHubConfigPath

internal typealias EngHubComponentFactory = (EngHubConfig, GitHubApiRestConfig) -> EngHubComponent

internal data class LoadedEngHubDependencies(
    val config: EngHubConfig,
    val viewModel: EngHubViewModel,
    val settingsViewModel: EngHubSettingsViewModel,
)

internal fun loadEngHubDependencies(
    loadConfig: () -> EngHubConfig = ::loadEngHubConfig,
    loadGitHubSettingsConfig: () -> LoadedGitHubConfig = { loadGitHubSettings(gitHubConfigPath) },
    componentFactory: EngHubComponentFactory = ::createEngHubComponent,
): LoadedEngHubDependencies {
    val config = loadConfig()
    val loadedGitHubConfig = loadGitHubSettingsConfig()
    return LoadedEngHubDependencies(
        config = config,
        viewModel = componentFactory(config, loadedGitHubConfig.toApiRestConfig()).viewModel,
        settingsViewModel = EngHubSettingsViewModel(
            engHubConfig = config,
            gitHubConfig = loadedGitHubConfig.config,
            gitHubSecret = loadedGitHubConfig.secret,
        ),
    )
}
