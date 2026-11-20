package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret

internal fun settingsState(
    repositories: List<LocalRepositoryConfig> = listOf(
        LocalRepositoryConfig(path = "/workspace/api", setupCommands = listOf("prepare")),
        LocalRepositoryConfig(path = "/workspace/web"),
    ),
    gitHubTokenPath: String = "/secrets/github.json",
    gitHubToken: String = "token",
    config: EngHubConfig = EngHubConfig(),
): EngHubSettingsUiState = createEngHubSettingsUiState(
    engHubConfig = config.copy(localRepositories = repositories),
    gitHubConfig = GitHubConfig(tokenPath = gitHubTokenPath),
    gitHubSecret = GitHubSecret(githubToken = gitHubToken),
)

internal fun EngHubSettingsUiState.planFor(
    navigation: EngHubSettingsNavigationState = initialSettingsNavigationState(this),
): SettingsRenderPlan = settingsRenderPlan(this, navigation)

internal fun EngHubSettingsUiState.repositoriesSection(
    navigation: EngHubSettingsNavigationState = initialSettingsNavigationState(this),
): SettingsSectionView = settingsRenderPlan(this, navigation).sections.single { section ->
    section.section == EngHubSettingsSection.Repositories
}

internal fun EngHubSettingsUiState.withPath(
    repositoryId: Long,
    path: String,
): EngHubSettingsUiState = copy(
    localRepositories = localRepositories.map { repository ->
        if (repository.id == repositoryId) repository.copy(path = path) else repository
    },
)
