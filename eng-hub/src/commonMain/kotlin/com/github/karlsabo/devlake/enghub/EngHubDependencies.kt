package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsPersistence
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubSettingsViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.GitHubAccessPersistence
import com.github.karlsabo.devlake.enghub.viewmodel.GitLabAccessPersistence
import com.github.karlsabo.devlake.enghub.viewmodel.LoadedAccessConfigs
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubConfigStore
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriteException
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.github.config.loadGitHubSettingsIfPresent
import com.github.karlsabo.gitlab.GitLabRestApi
import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.GitLabSecretStore
import com.github.karlsabo.gitlab.config.GitLabSecretWriteException
import com.github.karlsabo.gitlab.config.LoadedGitLabConfig
import com.github.karlsabo.gitlab.config.loadGitLabSecretIfPresent
import com.github.karlsabo.tools.gitHubConfigPath
import kotlinx.io.files.Path

internal typealias EngHubComponentFactory = (EngHubConfig, GitHubApiRestConfig) -> EngHubComponent

internal data class GitHubSettingsInfrastructure(
    val filePicker: FilePicker = createFilePicker(),
    val configStore: GitHubConfigStore = GitHubConfigStore(),
    val protectedPaths: List<Path> = transactionPaths(engHubConfigPath),
)

internal data class GitLabSettingsInfrastructure(
    val secretStore: GitLabSecretStore = GitLabSecretStore(),
    /** Protected in addition to the Eng Hub configuration path; the secret path must not alias any of them. */
    val protectedPaths: List<Path> = transactionPaths(gitHubConfigPath),
    val loadSecret: (Path) -> GitLabSecret? = { path -> loadGitLabSecretIfPresent(path) },
)

internal data class SettingsInfrastructure(
    val gitHub: GitHubSettingsInfrastructure = GitHubSettingsInfrastructure(),
    val gitLab: GitLabSettingsInfrastructure = GitLabSettingsInfrastructure(),
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
    infrastructure: SettingsInfrastructure = SettingsInfrastructure(),
): LoadedEngHubDependencies {
    val config = loadConfig()?.takeIf(EngHubConfig::isPersistenceValid) ?: EngHubConfig()
    val loadedGitHubConfig = loadGitHubSettingsConfig() ?: emptyGitHubConfig()
    val loadedGitLabConfig = loadGitLabSettings(config, infrastructure.gitLab.loadSecret)
    val component = componentFactory(config, loadedGitHubConfig.toApiRestConfig())
    val viewModel = component.viewModel
    viewModel.initializeGitHubAccessReadiness(loadedGitHubConfig.isAccessReady())
    viewModel.updateGitLabAccess(loadedGitLabConfig.readyApiConfig())
    val persistence = createSettingsPersistence(
        viewModel = viewModel,
        gitHubConfigFilePath = gitHubConfigFilePath,
        gitHubSettingsInfrastructure = infrastructure.gitHub,
        gitLabSettingsInfrastructure = infrastructure.gitLab,
    )
    return LoadedEngHubDependencies(
        config = config,
        viewModel = viewModel,
        settingsViewModel = EngHubSettingsViewModel(
            engHubConfig = config,
            loadedAccessConfigs = LoadedAccessConfigs(loadedGitHubConfig, loadedGitLabConfig),
            directoryPicker = component.directoryPicker,
            filePicker = infrastructure.gitHub.filePicker,
            persistence = persistence,
        ),
    )
}

private fun createSettingsPersistence(
    viewModel: EngHubViewModel,
    gitHubConfigFilePath: Path,
    gitHubSettingsInfrastructure: GitHubSettingsInfrastructure,
    gitLabSettingsInfrastructure: GitLabSettingsInfrastructure,
): EngHubSettingsPersistence {
    val gitHubConfigStore = gitHubSettingsInfrastructure.configStore
    val protectedConfigPaths = gitHubSettingsInfrastructure.protectedPaths
    return EngHubSettingsPersistence(
        updateConfig = viewModel::updateConfig,
        gitHub = GitHubAccessPersistence(
            secretWriter = gitHubConfigStore,
            validateSecretPath = { secretPath ->
                gitHubConfigStore.validateSecretPath(gitHubConfigFilePath, secretPath, protectedConfigPaths)
            },
            saveAccess = { secretPath, secret ->
                gitHubConfigStore.saveAccess(gitHubConfigFilePath, secretPath, secret, protectedConfigPaths)
            },
            onAccessCommitted = { loaded ->
                if (loaded.isAccessReady()) {
                    viewModel.updateGitHubAccess(
                        gitHubServices = createEngHubGitHubServices(loaded.toApiRestConfig()),
                        isReady = true,
                    )
                } else {
                    viewModel.initializeGitHubAccessReadiness(isReady = false)
                }
            },
        ),
        gitLab = GitLabAccessPersistence(
            saveSecret = { secretPath, secret ->
                gitLabSettingsInfrastructure.secretStore.save(secretPath, secret)
            },
            validateSecretPath = { secretPath ->
                validateGitLabSecretPath(gitHubConfigStore, secretPath, gitLabSettingsInfrastructure)
            },
            onAccessCommitted = viewModel.updateGitLabAccess,
            validateAccess = ::validateGitLabAccess,
        ),
        committedConfigUpdates = viewModel.configStateFlow,
    )
}

private fun loadGitLabSettings(
    config: EngHubConfig,
    loadSecret: (Path) -> GitLabSecret?,
): LoadedGitLabConfig {
    val secretPath = config.gitLabTokenPath.takeIf { it.isNotBlank() }
    val secret = secretPath?.let { loadSecret(Path(it)) } ?: GitLabSecret("")
    return LoadedGitLabConfig(
        instanceUrl = config.gitLabInstanceUrl,
        tokenPath = config.gitLabTokenPath,
        secret = secret,
    )
}

private fun validateGitLabSecretPath(
    gitHubConfigStore: GitHubConfigStore,
    secretPath: Path,
    infrastructure: GitLabSettingsInfrastructure,
) {
    try {
        gitHubConfigStore.validateSecretPath(engHubConfigPath, secretPath, infrastructure.protectedPaths)
    } catch (error: GitHubSecretWriteException) {
        throw GitLabSecretWriteException(error.message ?: "Invalid GitLab secret path: $secretPath", error)
    }
}

private suspend fun validateGitLabAccess(apiConfig: GitLabApiRestConfig): String {
    val api = GitLabRestApi(apiConfig)
    try {
        return api.getAuthenticatedUser().username
    } finally {
        api.close()
    }
}

private fun LoadedGitLabConfig.readyApiConfig(): GitLabApiRestConfig? = if (isAccessReady()) toApiRestConfig() else null

private fun transactionPaths(configPath: Path): List<Path> = listOf(
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
