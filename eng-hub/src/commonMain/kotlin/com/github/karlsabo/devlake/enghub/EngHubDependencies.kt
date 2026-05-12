package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.tools.gitHubConfigPath

internal typealias EngHubComponentFactory = (EngHubConfig, GitHubApiRestConfig) -> EngHubComponent

internal data class LoadedEngHubDependencies(
    val config: EngHubConfig,
    val viewModel: EngHubViewModel,
)

internal fun loadEngHubDependencies(
    loadConfig: () -> EngHubConfig = ::loadEngHubConfig,
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    componentFactory: EngHubComponentFactory = ::createEngHubComponent,
): LoadedEngHubDependencies {
    val config = loadConfig()
    val gitHubApiConfig = loadGitHubApiConfig()
    return LoadedEngHubDependencies(
        config = config,
        viewModel = componentFactory(config, gitHubApiConfig).viewModel,
    )
}

internal fun loadEngHubViewModel(
    loadConfig: () -> EngHubConfig = ::loadEngHubConfig,
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    componentFactory: EngHubComponentFactory = ::createEngHubComponent,
): EngHubViewModel {
    return loadEngHubDependencies(
        loadConfig = loadConfig,
        loadGitHubApiConfig = loadGitHubApiConfig,
        componentFactory = componentFactory,
    ).viewModel
}
