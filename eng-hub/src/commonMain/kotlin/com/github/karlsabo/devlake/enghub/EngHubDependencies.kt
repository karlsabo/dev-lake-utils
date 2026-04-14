package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.GitWorktreeService
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.system.DesktopLauncher
import com.github.karlsabo.system.DesktopLauncherService
import com.github.karlsabo.tools.gitHubConfigPath

internal data class EngHubDependencies(
    val gitHubApi: GitHubApi,
    val gitHubNotificationService: GitHubNotificationService,
    val gitWorktreeApi: GitWorktreeApi,
    val desktopLauncher: DesktopLauncher,
)

internal typealias EngHubDependencyProvider = (GitHubApiRestConfig) -> EngHubDependencies

internal val defaultEngHubDependencyProvider: EngHubDependencyProvider = { gitHubApiConfig ->
    val gitHubApi = GitHubRestApi(gitHubApiConfig)
    EngHubDependencies(
        gitHubApi = gitHubApi,
        gitHubNotificationService = GitHubNotificationService(gitHubApi),
        gitWorktreeApi = GitWorktreeService(),
        desktopLauncher = DesktopLauncherService(),
    )
}

internal fun loadEngHubViewModel(
    loadConfig: () -> EngHubConfig = ::loadEngHubConfig,
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    dependencyProvider: EngHubDependencyProvider = defaultEngHubDependencyProvider,
): EngHubViewModel {
    val config = loadConfig()
    val dependencies = dependencyProvider(loadGitHubApiConfig())

    return EngHubViewModel(
        gitHubApi = dependencies.gitHubApi,
        gitHubNotificationService = dependencies.gitHubNotificationService,
        gitWorktreeApi = dependencies.gitWorktreeApi,
        desktopLauncher = dependencies.desktopLauncher,
        config = config,
    )
}
