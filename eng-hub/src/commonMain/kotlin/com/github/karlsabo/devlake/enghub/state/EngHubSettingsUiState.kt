package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret

private const val MASKED_TOKEN = "••••••••"

class GitHubTokenUiState internal constructor(
    internal val value: String,
) {
    val maskedValue: String = if (value.isEmpty()) "" else MASKED_TOKEN

    override fun toString(): String = "GitHubTokenUiState()"
}

data class EngHubSettingsUiState(
    val organizationIds: List<String>,
    val pollIntervalSeconds: String,
    val pollIntervalError: String? = null,
    val worktreePollIntervalSeconds: String,
    val repositoriesBaseDir: String,
    val gitHubAuthor: String,
    val planningMarkdownDir: String,
    val localRepositories: List<SettingsLocalRepositoryUiState>,
    val setupShell: String,
    val gitHubTokenPath: String,
    val gitHubToken: GitHubTokenUiState,
)

data class SettingsLocalRepositoryUiState(
    val path: String,
    val setupCommands: List<String>,
)

internal fun createEngHubSettingsUiState(
    engHubConfig: EngHubConfig,
    gitHubConfig: GitHubConfig,
    gitHubSecret: GitHubSecret,
): EngHubSettingsUiState = EngHubSettingsUiState(
    organizationIds = engHubConfig.organizationIds,
    pollIntervalSeconds = (engHubConfig.pollIntervalMs / 1_000).toString(),
    worktreePollIntervalSeconds = (engHubConfig.worktreePollIntervalMs / 1_000).toString(),
    repositoriesBaseDir = engHubConfig.repositoriesBaseDir,
    gitHubAuthor = engHubConfig.gitHubAuthor,
    planningMarkdownDir = engHubConfig.planningMarkdownDir,
    localRepositories = engHubConfig.localRepositories.map { repository ->
        SettingsLocalRepositoryUiState(
            path = repository.path,
            setupCommands = repository.setupCommands,
        )
    },
    setupShell = engHubConfig.setupShell,
    gitHubTokenPath = gitHubConfig.tokenPath,
    gitHubToken = GitHubTokenUiState(gitHubSecret.githubToken),
)
