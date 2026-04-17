package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.tools.gitHubConfigPath

internal typealias EngHubComponentFactory = (EngHubConfig, GitHubApiRestConfig) -> EngHubComponent

internal fun loadEngHubViewModel(
    loadConfig: () -> EngHubConfig = ::loadEngHubConfig,
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    componentFactory: EngHubComponentFactory = ::createEngHubComponent,
): EngHubViewModel {
    val config = loadConfig()
    val gitHubApiConfig = loadGitHubApiConfig()
    return componentFactory(config, gitHubApiConfig).viewModel
}
