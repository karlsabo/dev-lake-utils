package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsPersistence
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubConfigStore
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.github.config.loadGitHubSettingsIfPresent
import com.github.karlsabo.tools.gitHubConfigPath
import kotlinx.io.files.Path

internal typealias EngHubComponentFactory = (EngHubConfig, GitHubApiRestConfig) -> EngHubComponent

internal data class GitHubSettingsInfrastructure(
    val filePicker: FilePicker = createFilePicker(),
    val configStore: GitHubConfigStore = GitHubConfigStore(),
    val protectedPaths: List<Path> = engHubTransactionPaths(engHubConfigPath),
)

internal data class LoadedEngHubDependencies(
    val config: EngHubConfig,
    val viewModel: EngHubViewModel,
    val settingsViewModel: EngHubSettingsViewModel,
)

internal fun loadEngHubDependencies(
    loadConfig: () -> EngHubConfig? = { loadEngHubConfigIfPresent() },
    gitHubConfigFilePath: Path = gitHubConfigPath,
    loadGitHubSettingsConfig: () -> LoadedGitHubConfig? = {
        loadGitHubSettingsForEngHub(gitHubConfigFilePath)
    },
    componentFactory: EngHubComponentFactory = ::createEngHubComponent,
    gitHubSettingsInfrastructure: GitHubSettingsInfrastructure = GitHubSettingsInfrastructure(),
): LoadedEngHubDependencies {
    val config = loadConfig()?.takeIf(EngHubConfig::isPersistenceValid) ?: EngHubConfig()
    val loadedGitHubConfig = loadGitHubSettingsConfig() ?: emptyGitHubConfig()
    val component = componentFactory(config, loadedGitHubConfig.toApiRestConfig())
    val viewModel = component.viewModel
    viewModel.initializeGitHubAccessReadiness(loadedGitHubConfig.isAccessReady())
    val gitHubConfigStore = gitHubSettingsInfrastructure.configStore
    val protectedConfigPaths = gitHubSettingsInfrastructure.protectedPaths
    return LoadedEngHubDependencies(
        config = config,
        viewModel = viewModel,
        settingsViewModel = EngHubSettingsViewModel(
            engHubConfig = config,
            loadedGitHubConfig = loadedGitHubConfig,
            directoryPicker = component.directoryPicker,
            filePicker = gitHubSettingsInfrastructure.filePicker,
            persistence = EngHubSettingsPersistence(
                updateConfig = viewModel::updateConfig,
                gitHubSecretWriter = gitHubConfigStore,
                validateGitHubSecretPath = { secretPath ->
                    gitHubConfigStore.validateSecretPath(
                        gitHubConfigFilePath,
                        secretPath,
                        protectedConfigPaths,
                    )
                },
                saveGitHubAccess = { secretPath, secret ->
                    gitHubConfigStore.saveAccess(
                        gitHubConfigFilePath,
                        secretPath,
                        secret,
                        protectedConfigPaths,
                    )
                },
                onGitHubAccessCommitted = { loaded ->
                    if (loaded.isAccessReady()) {
                        viewModel.updateGitHubAccess(
                            gitHubServices = createEngHubGitHubServices(loaded.toApiRestConfig()),
                            isReady = true,
                        )
                    } else {
                        viewModel.initializeGitHubAccessReadiness(isReady = false)
                    }
                },
                committedConfigUpdates = viewModel.configStateFlow,
            ),
        ),
    )
}

private fun engHubTransactionPaths(configPath: Path): List<Path> = listOf(
    configPath,
    Path("$configPath.new"),
    Path("$configPath.bak"),
)

internal fun loadGitHubSettingsForEngHub(configPath: Path): LoadedGitHubConfig {
    val loadedSettings = loadGitHubSettingsIfPresent(configPath)
    return loadedSettings ?: emptyGitHubConfig()
}

private fun LoadedGitHubConfig.isAccessReady(): Boolean = (
    config.tokenPath.isNotBlank() && secret.githubToken.isNotBlank()
    )

private fun emptyGitHubConfig(): LoadedGitHubConfig = LoadedGitHubConfig(
    config = GitHubConfig(tokenPath = ""),
    secret = GitHubSecret(githubToken = ""),
)
